#include <jni.h>
#include <string>
#include <exception>
#include <cstring>
#include <cstdlib>

#include "lua.h"
#include "lualib.h"
#include "luacode.h"

// Storing JavaVM globally
static JavaVM* g_JavaVM = nullptr;

// Helper to throw LuauException on Java side
static void throw_luau_exception(JNIEnv* env, const char* message) {
    jclass exClass = env->FindClass("io/novela/luau/LuauException");
    if (exClass) {
        env->ThrowNew(exClass, message);
    }
}

// Maps native Luau type tag integers to unified Java/Kotlin LuauType ordinals
static jint map_lua_type(int t) {
    switch (t) {
        case LUA_TNIL: return 1;          // LuauType.NIL
        case LUA_TBOOLEAN: return 2;      // LuauType.BOOLEAN
        case LUA_TLIGHTUSERDATA: return 3;// LuauType.LIGHTUSERDATA
        case LUA_TNUMBER: return 4;       // LuauType.NUMBER
        case LUA_TINTEGER: return 5;      // LuauType.INTEGER
        case LUA_TVECTOR: return 6;       // LuauType.VECTOR
        case LUA_TSTRING: return 7;       // LuauType.STRING
        case LUA_TTABLE: return 8;        // LuauType.TABLE
        case LUA_TFUNCTION: return 9;     // LuauType.FUNCTION
        case LUA_TUSERDATA: return 10;    // LuauType.USERDATA
        case LUA_TTHREAD: return 11;      // LuauType.THREAD
        case LUA_TBUFFER: return 12;      // LuauType.BUFFER
        default: return 0;                // LuauType.NONE / unknown
    }
}

// Thread attachment helper for JNI callbacks
static JNIEnv* get_jni_env(bool* attached) {
    JNIEnv* env = nullptr;
    jint res = g_JavaVM->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
#ifdef __ANDROID__
        res = g_JavaVM->AttachCurrentThread(&env, nullptr);
#else
        res = g_JavaVM->AttachCurrentThread(reinterpret_cast<void**>(&env), nullptr);
#endif
        if (res == JNI_OK) {
            *attached = true;
        }
    } else {
        *attached = false;
    }
    return env;
}

static void release_jni_env(bool attached) {
    if (attached) {
        g_JavaVM->DetachCurrentThread();
    }
}

// Native Luau VM destructors for userdata GC cycles
static void java_callback_destructor(void* ud) {
    bool attached = false;
    JNIEnv* env = get_jni_env(&attached);
    if (env) {
        jobject* refPtr = reinterpret_cast<jobject*>(ud);
        if (refPtr && *refPtr) {
            env->DeleteGlobalRef(*refPtr);
            *refPtr = nullptr;
        }
    }
    release_jni_env(attached);
}

static void java_object_destructor(void* ud) {
    bool attached = false;
    JNIEnv* env = get_jni_env(&attached);
    if (env) {
        jobject* refPtr = reinterpret_cast<jobject*>(ud);
        if (refPtr && *refPtr) {
            env->DeleteGlobalRef(*refPtr);
            *refPtr = nullptr;
        }
    }
    release_jni_env(attached);
}

// Metatables creation helper for custom types
static void create_metatables(lua_State* L) {
    luaL_newmetatable(L, "JavaCallbackMetatable");
    lua_pop(L, 1);

    luaL_newmetatable(L, "JavaObjectMetatable");
    lua_pop(L, 1);
}

// Callback dispatcher (Lua C closure to Kotlin dispatch bridge)
static int jni_callback_dispatcher(lua_State* L) {
    bool attached = false;
    JNIEnv* env = get_jni_env(&attached);
    if (!env) {
        lua_pushstring(L, "Failed to acquire JNI environment");
        lua_error(L);
        return 0;
    }

    if (env->PushLocalFrame(16) < 0) {
        release_jni_env(attached);
        lua_pushstring(L, "Failed to push JNI local frame");
        lua_error(L);
        return 0;
    }

    jobject* refPtr = reinterpret_cast<jobject*>(lua_touserdata(L, lua_upvalueindex(1)));
    if (!refPtr || !*refPtr) {
        env->PopLocalFrame(nullptr);
        release_jni_env(attached);
        lua_pushstring(L, "Java callback reference is null");
        lua_error(L);
        return 0;
    }
    jobject callbackObj = *refPtr;

    jclass cbClass = env->GetObjectClass(callbackObj);
    jmethodID cbMethod = env->GetMethodID(cbClass, "invoke", "(Lio/novela/luau/LuauState;)I");
    if (!cbMethod) {
        env->PopLocalFrame(nullptr);
        release_jni_env(attached);
        lua_pushstring(L, "Failed to find invoke method on callback object");
        lua_error(L);
        return 0;
    }

    jclass stateClass = env->FindClass("io/novela/luau/LuauState");
    jmethodID fromHandleMethod = env->GetStaticMethodID(stateClass, "fromHandle", "(J)Lio/novela/luau/LuauState;");
    jobject tempState = env->CallStaticObjectMethod(stateClass, fromHandleMethod, reinterpret_cast<jlong>(L));

    // Call Kotlin callback method
    jint results_count = env->CallIntMethod(callbackObj, cbMethod, tempState);

    const char* error_to_raise = nullptr;
    char error_buf[512] = {0};
    bool yield_requested = false;
    jint yield_nresults = 0;

    {
        jthrowable exception = env->ExceptionOccurred();
        if (exception) {
            env->ExceptionClear();

            // Check if it is a yield request
            jclass yieldExClass = env->FindClass("io/novela/luau/LuauYieldException");
            if (env->IsInstanceOf(exception, yieldExClass)) {
                jfieldID nresultsField = env->GetFieldID(yieldExClass, "nresults", "I");
                yield_nresults = env->GetIntField(exception, nresultsField);
                yield_requested = true;
            } else {
                jclass exClass = env->GetObjectClass(exception);
                jmethodID getMessageMethod = env->GetMethodID(exClass, "getMessage", "()Ljava/lang/String;");
                jstring msgStr = (jstring)env->CallObjectMethod(exception, getMessageMethod);
                const char* msg = nullptr;
                if (msgStr) {
                    msg = env->GetStringUTFChars(msgStr, nullptr);
                }
                if (msg) {
                    std::strncpy(error_buf, msg, sizeof(error_buf) - 1);
                    env->ReleaseStringUTFChars(msgStr, msg);
                } else {
                    std::strcpy(error_buf, "Exception occurred in Java callback");
                }
                error_to_raise = error_buf;
            }
            env->PopLocalFrame(nullptr);
            release_jni_env(attached);
        } else {
            env->PopLocalFrame(nullptr);
            release_jni_env(attached);
        }
    }

    if (yield_requested) {
        return lua_yield(L, yield_nresults); // safely yield: no C++ objects in scope
    }

    if (error_to_raise) {
        lua_pushstring(L, error_to_raise);
        lua_error(L); // safely longjmp: no C++ objects in scope
        return 0;
    }

    return results_count;
}

extern "C" {

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_JavaVM = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jlong JNICALL Java_io_novela_luau_LuauState_nativeCreateState(JNIEnv* env, jclass clazz) {
    try {
        lua_State* L = luaL_newstate();
        if (!L) {
            return 0;
        }
        luaL_openlibs(L);
        create_metatables(L);
        return reinterpret_cast<jlong>(L);
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
        return 0;
    } catch (...) {
        throw_luau_exception(env, "Unknown error during nativeState creation");
        return 0;
    }
}

JNIEXPORT jlong JNICALL Java_io_novela_luau_LuauState_nativeCreateStateEmpty(JNIEnv* env, jclass clazz) {
    try {
        lua_State* L = luaL_newstate();
        if (!L) {
            return 0;
        }
        create_metatables(L);
        return reinterpret_cast<jlong>(L);
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
        return 0;
    } catch (...) {
        throw_luau_exception(env, "Unknown error during empty nativeState creation");
        return 0;
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeCloseState(JNIEnv* env, jclass clazz, jlong handle) {
    try {
        if (handle == 0) {
            return;
        }
        lua_State* L = reinterpret_cast<lua_State*>(handle);
        lua_close(L);
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
    } catch (...) {
        throw_luau_exception(env, "Unknown error during nativeState closing");
    }
}

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeExecute(JNIEnv* env, jobject thiz, jlong handle, jstring script) {
    if (handle == 0) {
        throw_luau_exception(env, "Attempted to execute on a null state handle");
        return -1;
    }
    if (!script) {
        jclass exClass = env->FindClass("java/lang/NullPointerException");
        if (exClass) {
            env->ThrowNew(exClass, "Script parameter cannot be null");
        }
        return -1;
    }

    lua_State* L = reinterpret_cast<lua_State*>(handle);

    jsize len = env->GetStringUTFLength(script);
    const char* utfChars = env->GetStringUTFChars(script, nullptr);
    if (!utfChars) {
        return -1;
    }

    std::string script_str(utfChars, len);
    env->ReleaseStringUTFChars(script, utfChars);

    try {
        size_t bytecode_size = 0;
        char* bytecode = luau_compile(script_str.c_str(), script_str.size(), nullptr, &bytecode_size);
        if (!bytecode) {
            jclass exClass = env->FindClass("java/lang/OutOfMemoryError");
            if (exClass) {
                env->ThrowNew(exClass, "Failed to allocate memory for compiled bytecode");
            }
            return -1;
        }

        if (bytecode_size > 0 && bytecode[0] == '\0') {
            std::string err_msg(bytecode + 1, bytecode_size - 1);
            free(bytecode);
            throw_luau_exception(env, err_msg.c_str());
            return -1;
        }

        int load_status = luau_load(L, "=script", bytecode, bytecode_size, 0);
        free(bytecode);

        if (load_status != 0) {
            const char* err = lua_tostring(L, -1);
            std::string err_msg = err ? err : "Failed to load compiled bytecode into VM";
            lua_pop(L, 1);
            throw_luau_exception(env, err_msg.c_str());
            return load_status;
        }

        int run_status = lua_pcall(L, 0, 0, 0);
        if (run_status != 0) {
            const char* err = lua_tostring(L, -1);
            std::string err_msg = err ? err : "Luau runtime execution error";
            lua_pop(L, 1);
            throw_luau_exception(env, err_msg.c_str());
            return run_status;
        }

        return 0;
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
        return -1;
    } catch (...) {
        throw_luau_exception(env, "Unknown C++ exception occurred during native execute");
        return -1;
    }
}

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeLoad(JNIEnv* env, jobject thiz, jlong handle, jstring script) {
    if (handle == 0) {
        throw_luau_exception(env, "Attempted to load on a null state handle");
        return -1;
    }
    if (!script) {
        jclass exClass = env->FindClass("java/lang/NullPointerException");
        if (exClass) {
            env->ThrowNew(exClass, "Script parameter cannot be null");
        }
        return -1;
    }

    lua_State* L = reinterpret_cast<lua_State*>(handle);

    jsize len = env->GetStringUTFLength(script);
    const char* utfChars = env->GetStringUTFChars(script, nullptr);
    if (!utfChars) {
        return -1;
    }

    std::string script_str(utfChars, len);
    env->ReleaseStringUTFChars(script, utfChars);

    try {
        size_t bytecode_size = 0;
        char* bytecode = luau_compile(script_str.c_str(), script_str.size(), nullptr, &bytecode_size);
        if (!bytecode) {
            jclass exClass = env->FindClass("java/lang/OutOfMemoryError");
            if (exClass) {
                env->ThrowNew(exClass, "Failed to allocate memory for compiled bytecode");
            }
            return -1;
        }

        if (bytecode_size > 0 && bytecode[0] == '\0') {
            std::string err_msg(bytecode + 1, bytecode_size - 1);
            free(bytecode);
            throw_luau_exception(env, err_msg.c_str());
            return -1;
        }

        int load_status = luau_load(L, "=script", bytecode, bytecode_size, 0);
        free(bytecode);

        if (load_status != 0) {
            const char* err = lua_tostring(L, -1);
            std::string err_msg = err ? err : "Failed to load compiled bytecode into VM";
            lua_pop(L, 1);
            throw_luau_exception(env, err_msg.c_str());
            return load_status;
        }

        return 0;
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
        return -1;
    } catch (...) {
        throw_luau_exception(env, "Unknown C++ exception occurred during native load");
        return -1;
    }
}

// --- JNI Stack operations ---

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeGetTop(JNIEnv* env, jobject thiz, jlong handle) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    return lua_gettop(L);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeSetTop(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_settop(L, idx);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePop(JNIEnv* env, jobject thiz, jlong handle, jint n) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_pop(L, n);
}

// --- JNI Push operations ---

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushNil(JNIEnv* env, jobject thiz, jlong handle) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_pushnil(L);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushBoolean(JNIEnv* env, jobject thiz, jlong handle, jboolean b) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_pushboolean(L, b);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushInteger(JNIEnv* env, jobject thiz, jlong handle, jint i) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_pushinteger(L, i);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushNumber(JNIEnv* env, jobject thiz, jlong handle, jdouble n) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_pushnumber(L, n);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushString(JNIEnv* env, jobject thiz, jlong handle, jstring s) {
    if (!s) {
        return;
    }
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    jsize len = env->GetStringUTFLength(s);
    const char* str = env->GetStringUTFChars(s, nullptr);
    if (str) {
        lua_pushlstring(L, str, len);
        env->ReleaseStringUTFChars(s, str);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushByteArray(JNIEnv* env, jobject thiz, jlong handle, jbyteArray bytes) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    if (!bytes) {
        lua_pushnil(L);
        return;
    }
    jsize len = env->GetArrayLength(bytes);
    jbyte* elements = env->GetByteArrayElements(bytes, nullptr);
    if (elements) {
        lua_pushlstring(L, reinterpret_cast<const char*>(elements), len);
        env->ReleaseByteArrayElements(bytes, elements, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushBuffer(JNIEnv* env, jobject thiz, jlong handle, jbyteArray bytes) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    if (!bytes) {
        lua_pushnil(L);
        return;
    }
    jsize len = env->GetArrayLength(bytes);
    jbyte* elements = env->GetByteArrayElements(bytes, nullptr);
    if (elements) {
        void* buf = lua_newbuffer(L, len);
        if (buf && len > 0) {
            std::memcpy(buf, elements, len);
        }
        env->ReleaseByteArrayElements(bytes, elements, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushValue(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_pushvalue(L, idx);
}

// --- JNI Get Query operations ---

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeType(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    return map_lua_type(lua_type(L, idx));
}

JNIEXPORT jboolean JNICALL Java_io_novela_luau_LuauState_nativeGetBoolean(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    return lua_toboolean(L, idx);
}

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeGetInteger(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    return lua_tointeger(L, idx);
}

JNIEXPORT jdouble JNICALL Java_io_novela_luau_LuauState_nativeGetNumber(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    return lua_tonumber(L, idx);
}

JNIEXPORT jstring JNICALL Java_io_novela_luau_LuauState_nativeGetString(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    size_t len = 0;
    const char* str = lua_tolstring(L, idx, &len);
    if (!str) {
        return nullptr;
    }
    std::string s(str, len);
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jbyteArray JNICALL Java_io_novela_luau_LuauState_nativeGetByteArray(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    size_t len = 0;
    const char* data = nullptr;
    int t = lua_type(L, idx);
    if (t == LUA_TSTRING) {
        data = lua_tolstring(L, idx, &len);
    } else if (t == LUA_TBUFFER) {
        data = (const char*)lua_tobuffer(L, idx, &len);
    }
    if (!data) {
        return nullptr;
    }
    jbyteArray arr = env->NewByteArray(len);
    if (arr) {
        env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte*>(data));
    }
    return arr;
}

// --- JNI Table and Global operations ---

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeGetGlobal(JNIEnv* env, jobject thiz, jlong handle, jstring name) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    if (nameStr) {
        lua_getglobal(L, nameStr);
        env->ReleaseStringUTFChars(name, nameStr);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeSetGlobal(JNIEnv* env, jobject thiz, jlong handle, jstring name) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    if (nameStr) {
        lua_setglobal(L, nameStr);
        env->ReleaseStringUTFChars(name, nameStr);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeGetField(JNIEnv* env, jobject thiz, jlong handle, jint idx, jstring name) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    if (nameStr) {
        lua_getfield(L, idx, nameStr);
        env->ReleaseStringUTFChars(name, nameStr);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeSetField(JNIEnv* env, jobject thiz, jlong handle, jint idx, jstring name) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    const char* nameStr = env->GetStringUTFChars(name, nullptr);
    if (nameStr) {
        lua_setfield(L, idx, nameStr);
        env->ReleaseStringUTFChars(name, nameStr);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeRawGet(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_rawget(L, idx);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeRawSet(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_rawset(L, idx);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeCreateTable(JNIEnv* env, jobject thiz, jlong handle, jint narr, jint nrec) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_createtable(L, narr, nrec);
}

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeRawLen(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    return lua_objlen(L, idx);
}

// --- JNI Stack Manipulation Helpers ---

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeInsert(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_insert(L, idx);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeRemove(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_remove(L, idx);
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeReplace(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_replace(L, idx);
}

// --- Phase 3 Native Implementations ---

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushCallback(JNIEnv* env, jobject thiz, jlong handle, jobject callback) {
    try {
        lua_State* L = reinterpret_cast<lua_State*>(handle);
        if (!L) {
            throw_luau_exception(env, "State handle is null");
            return;
        }
        jobject globalRef = env->NewGlobalRef(callback);
        
        jobject* ud = reinterpret_cast<jobject*>(lua_newuserdatadtor(L, sizeof(jobject), java_callback_destructor));
        *ud = globalRef;
        
        luaL_getmetatable(L, "JavaCallbackMetatable");
        lua_setmetatable(L, -2);
        
        lua_pushcclosure(L, jni_callback_dispatcher, "jni_callback", 1);
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
    } catch (...) {
        throw_luau_exception(env, "Unknown error pushing callback");
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativePushUserdata(JNIEnv* env, jobject thiz, jlong handle, jobject obj) {
    try {
        lua_State* L = reinterpret_cast<lua_State*>(handle);
        if (!L) {
            throw_luau_exception(env, "State handle is null");
            return;
        }
        jobject globalRef = env->NewGlobalRef(obj);
        
        jobject* ud = reinterpret_cast<jobject*>(lua_newuserdatadtor(L, sizeof(jobject), java_object_destructor));
        *ud = globalRef;
        
        luaL_getmetatable(L, "JavaObjectMetatable");
        lua_setmetatable(L, -2);
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
    } catch (...) {
        throw_luau_exception(env, "Unknown error pushing userdata");
    }
}

JNIEXPORT jobject JNICALL Java_io_novela_luau_LuauState_nativeGetUserdata(JNIEnv* env, jobject thiz, jlong handle, jint idx) {
    try {
        lua_State* L = reinterpret_cast<lua_State*>(handle);
        if (!L) {
            throw_luau_exception(env, "State handle is null");
            return nullptr;
        }
        if (lua_type(L, idx) != LUA_TUSERDATA) {
            return nullptr;
        }
        jobject* refPtr = reinterpret_cast<jobject*>(lua_touserdata(L, idx));
        if (refPtr && *refPtr) {
            return *refPtr;
        }
        return nullptr;
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
        return nullptr;
    } catch (...) {
        throw_luau_exception(env, "Unknown error getting userdata");
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL Java_io_novela_luau_LuauState_nativeNewThread(JNIEnv* env, jobject thiz, jlong handle) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    lua_State* thread = lua_newthread(L);
    return reinterpret_cast<jlong>(thread);
}

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeResume(JNIEnv* env, jobject thiz, jlong threadHandle, jlong fromHandle, jint narg) {
    try {
        lua_State* thread = reinterpret_cast<lua_State*>(threadHandle);
        lua_State* from = reinterpret_cast<lua_State*>(fromHandle);
        if (!thread) {
            throw_luau_exception(env, "Thread handle is null");
            return -1;
        }
        int status = lua_resume(thread, from, narg);
        if (status != 0 && status != LUA_YIELD) {
            const char* err = lua_tostring(thread, -1);
            std::string err_msg = err ? err : "Unknown error during coroutine resume";
            lua_pop(thread, 1);
            throw_luau_exception(env, err_msg.c_str());
            return status;
        }
        return status;
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
        return -1;
    } catch (...) {
        throw_luau_exception(env, "Unknown error resuming coroutine");
        return -1;
    }
}

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativeStatus(JNIEnv* env, jobject thiz, jlong handle) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    return lua_status(L);
}

JNIEXPORT jint JNICALL Java_io_novela_luau_LuauState_nativePcall(JNIEnv* env, jobject thiz, jlong handle, jint nargs, jint nresults, jint errfunc) {
    try {
        lua_State* L = reinterpret_cast<lua_State*>(handle);
        if (!L) {
            throw_luau_exception(env, "State handle is null");
            return -1;
        }
        int status = lua_pcall(L, nargs, nresults, errfunc);
        if (status != 0) {
            const char* err = lua_tostring(L, -1);
            std::string err_msg = err ? err : "Luau execution error during pcall";
            lua_pop(L, 1);
            throw_luau_exception(env, err_msg.c_str());
        }
        return status;
    } catch (const std::exception& e) {
        throw_luau_exception(env, e.what());
        return -1;
    } catch (...) {
        throw_luau_exception(env, "Unknown error during native pcall");
        return -1;
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeCollectGarbage(JNIEnv* env, jobject thiz, jlong handle) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    if (L) {
        lua_gc(L, LUA_GCCOLLECT, 0);
    }
}

// --- Phase 4 Native Implementations ---

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeXmove(JNIEnv* env, jobject thiz, jlong fromHandle, jlong toHandle, jint n) {
    lua_State* from = reinterpret_cast<lua_State*>(fromHandle);
    lua_State* to = reinterpret_cast<lua_State*>(toHandle);
    if (from && to) {
        lua_xmove(from, to, n);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeOpenLibraries(JNIEnv* env, jobject thiz, jlong handle, jint libsMask) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    if (!L) return;

    if (libsMask & (1 << 0)) {
        lua_pushcfunction(L, luaopen_base, "luaopen_base");
        lua_pushstring(L, "");
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 1)) {
        lua_pushcfunction(L, luaopen_coroutine, "luaopen_coroutine");
        lua_pushstring(L, LUA_COLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 2)) {
        lua_pushcfunction(L, luaopen_table, "luaopen_table");
        lua_pushstring(L, LUA_TABLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 3)) {
        lua_pushcfunction(L, luaopen_os, "luaopen_os");
        lua_pushstring(L, LUA_OSLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 4)) {
        lua_pushcfunction(L, luaopen_string, "luaopen_string");
        lua_pushstring(L, LUA_STRLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 5)) {
        lua_pushcfunction(L, luaopen_bit32, "luaopen_bit32");
        lua_pushstring(L, LUA_BITLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 6)) {
        lua_pushcfunction(L, luaopen_buffer, "luaopen_buffer");
        lua_pushstring(L, LUA_BUFFERLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 7)) {
        lua_pushcfunction(L, luaopen_utf8, "luaopen_utf8");
        lua_pushstring(L, LUA_UTF8LIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 8)) {
        lua_pushcfunction(L, luaopen_math, "luaopen_math");
        lua_pushstring(L, LUA_MATHLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 9)) {
        lua_pushcfunction(L, luaopen_debug, "luaopen_debug");
        lua_pushstring(L, LUA_DBLIBNAME);
        lua_call(L, 1, 0);
    }
    if (libsMask & (1 << 10)) {
        lua_pushcfunction(L, luaopen_vector, "luaopen_vector");
        lua_pushstring(L, LUA_VECLIBNAME);
        lua_call(L, 1, 0);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeSandbox(JNIEnv* env, jobject thiz, jlong handle) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    if (L) {
        luaL_sandbox(L);
    }
}

JNIEXPORT void JNICALL Java_io_novela_luau_LuauState_nativeSandboxThread(JNIEnv* env, jobject thiz, jlong handle) {
    lua_State* L = reinterpret_cast<lua_State*>(handle);
    if (L) {
        luaL_sandboxthread(L);
    }
}

} // extern "C"
