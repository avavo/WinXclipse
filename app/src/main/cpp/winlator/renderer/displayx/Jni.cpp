// SPDX-License-Identifier: MIT
#include "Server.hpp"
#include <jni.h>
#include <android/log.h>

namespace {
struct ThreadEnv {
    JavaVM* vm;JNIEnv* environment=nullptr;bool attached;
    explicit ThreadEnv(JavaVM* machine):vm(machine),attached(vm->GetEnv(reinterpret_cast<void**>(&environment),JNI_VERSION_1_6)==JNI_EDETACHED) {
        if(attached&&vm->AttachCurrentThreadAsDaemon(&environment,nullptr)!=JNI_OK)environment=nullptr;
    }
    ~ThreadEnv(){if(attached&&environment)vm->DetachCurrentThread();}
};
struct Observer {
    JavaVM* vm{};jweak target{};jmethodID frame{};
    Observer(JNIEnv* e,jobject object) {
        e->GetJavaVM(&vm);target=e->NewWeakGlobalRef(object);
        jclass type=e->GetObjectClass(object);frame=e->GetMethodID(type,"onNativeFrame","(I)V");e->DeleteLocalRef(type);
    }
    template<class F> void withEnv(F body) {
        // A receiver is long-lived. Do not attach/detach a JVM thread 120 times
        // per second; detach once when that native worker actually exits.
        static thread_local std::unique_ptr<ThreadEnv> current;
        if(!current||current->vm!=vm)current=std::make_unique<ThreadEnv>(vm);
        if(current->environment)body(current->environment);
    }
    bool notify(uint32_t id) {bool delivered=false;withEnv([&](JNIEnv* e){
        jobject object=e->NewLocalRef(target);
        if(object){e->CallVoidMethod(object,frame,jint(id));e->DeleteLocalRef(object);delivered=true;}
        if(e->ExceptionCheck()){delivered=false;e->ExceptionClear();__android_log_print(ANDROID_LOG_ERROR,"DisplayX","Frame callback failed");}
    });return delivered;}
    ~Observer(){withEnv([&](JNIEnv* e){e->DeleteWeakGlobalRef(target);});}
};
}
#define JNI(name) Java_com_winlator_cmod_renderer_DisplayXSession_##name
extern "C" JNIEXPORT jlong JNICALL JNI(nativeCreate)(JNIEnv* e,jobject object,jstring path) {
    const char* name=e->GetStringUTFChars(path,nullptr);if(!name)return 0;
    try {
        auto observer=std::make_shared<Observer>(e,object);
        auto server=std::make_shared<displayx::Server>(name,[observer](uint32_t id){return observer->notify(id);});
        e->ReleaseStringUTFChars(path,name);return reinterpret_cast<jlong>(new std::shared_ptr<displayx::Server>(std::move(server)));
    } catch(const std::exception& error) {
        e->ReleaseStringUTFChars(path,name);
        __android_log_print(ANDROID_LOG_ERROR,"DisplayX","%s",error.what());return 0;
    }
}
extern "C" JNIEXPORT void JNICALL JNI(nativeClose)(JNIEnv*,jclass,jlong handle) {
    delete reinterpret_cast<std::shared_ptr<displayx::Server>*>(handle);
}
extern "C" JNIEXPORT void JNICALL JNI(nativeFpsLimit)(JNIEnv*,jclass,jlong handle,jint fps) {
    if(handle)(*reinterpret_cast<std::shared_ptr<displayx::Server>*>(handle))->setFpsLimit(uint32_t(fps<0?0:fps));
}

extern "C" JNIEXPORT void JNICALL JNI(nativeOptions)(JNIEnv*,jclass,jlong handle,jboolean every,jboolean performance) {
    if(handle)(*reinterpret_cast<std::shared_ptr<displayx::Server>*>(handle))->setOptions(every,performance);
}
