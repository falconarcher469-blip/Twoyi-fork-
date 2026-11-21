// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

use jni::objects::JValue;
use jni::sys::{jclass, jfloat, jint, jobject, jstring, JNI_ERR};
use jni::JNIEnv;
use jni::{JavaVM, NativeMethod};
use log::{debug, error, info, Level};
use ndk_sys;
use std::ffi::c_void;

use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;

use android_logger::Config;

use std::fs::File;
use std::os::unix::process::CommandExt;
use std::process::{Command, Stdio};

mod input;
mod renderer_bindings;
mod seccomp;

macro_rules! jni_method {
    ( $name: tt, $method:tt, $signature:expr ) => {{
        jni::NativeMethod {
            name: jni::strings::JNIString::from(stringify!($name)),
            sig: jni::strings::JNIString::from($signature),
            fn_ptr: $method as *mut c_void,
        }
    }};
}

static RENDERER_STARTED: AtomicBool = AtomicBool::new(false);

#[no_mangle]
pub fn renderer_init(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    loader: jstring,
    data_dir: jstring,
    xdpi: jfloat,
    ydpi: jfloat,
    fps: jint,
) {
    debug!("renderer_init");
    seccomp::install_sigsys_handler();

    let window = unsafe { ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface) };

    let window = match std::ptr::NonNull::new(window) {
        Some(x) => x,
        None => {
            error!("ANativeWindow_fromSurface was null!");
            return;
        }
    };

    let window = unsafe { ndk::native_window::NativeWindow::from_ptr(window) };

    let width = window.width();
    let height = window.height();

    info!(
        "renderer_init width: {}, height: {}, fps: {}",
        width, height, fps
    );

    if RENDERER_STARTED
        .compare_exchange(false, true, Ordering::Acquire, Ordering::Relaxed)
        .is_err()
    {
        let win = window.ptr().as_ptr() as *mut c_void;
        unsafe {
            renderer_bindings::setNativeWindow(win);
            renderer_bindings::resetSubWindow(win, 0, 0, width, height, width, height, 1.0, 0.0);
        }
    } else {
        let data_dir_str: String = match env.get_string(data_dir.into()) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("failed to read data_dir string: {:?}", e);
                return;
            }
        };

        let loader_path: String = match env.get_string(loader.into()) {
            Ok(s) => s.into(),
            Err(e) => {
                error!("failed to read loader string: {:?}", e);
                return;
            }
        };

        input::start_input_system(width, height, data_dir_str.clone());

        thread::spawn(move || {
            let win = window.ptr().as_ptr() as *mut c_void;
            info!("win: {:#?}", win);
            unsafe {
                renderer_bindings::startOpenGLRenderer(
                    win,
                    width,
                    height,
                    xdpi as i32,
                    ydpi as i32,
                    fps as i32,
                );
            }
        });

        let working_dir = format!("{}/rootfs", data_dir_str);
        let log_path = format!("{}/log.txt", data_dir_str);

        let outputs = match File::create(&log_path) {
            Ok(f) => f,
            Err(e) => {
                error!("failed to create log file {}: {:?}", log_path, e);
                return;
            }
        };
        let errors = match outputs.try_clone() {
            Ok(f) => f,
            Err(e) => {
                error!("failed to clone log fd: {:?}", e);
                return;
            }
        };

        let mut cmd = Command::new("./init");
        cmd.current_dir(&working_dir)
            .env("TYLOADER", loader_path)
            .env("ANDROID_ROOT", "/system")
            .env("ANDROID_DATA", "/data")
            .stdout(Stdio::from(outputs))
            .stderr(Stdio::from(errors));

        unsafe {
            cmd.pre_exec(|| {
                seccomp::configure_child_process();
                Ok(())
            });
        }

        if let Err(e) = cmd.spawn() {
            error!("failed to spawn ./init in {}: {:?}", working_dir, e);
        }
    }
}

#[no_mangle]
pub fn renderer_reset_window(
    env: JNIEnv,
    _clz: jclass,
    surface: jobject,
    _top: jint,
    _left: jint,
    width: jint,
    height: jint,
) {
    debug!("reset_window");
    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        if !window.is_null() {
            renderer_bindings::resetSubWindow(
                window as *mut c_void,
                0,
                0,
                width,
                height,
                width,
                height,
                1.0,
                0.0,
            );
        }
    }
}

#[no_mangle]
pub fn renderer_remove_window(env: JNIEnv, _clz: jclass, surface: jobject) {
    debug!("renderer_remove_window");

    unsafe {
        let window = ndk_sys::ANativeWindow_fromSurface(env.get_native_interface(), surface);
        if !window.is_null() {
            renderer_bindings::removeSubWindow(window as *mut c_void);
        }
    }
}

#[no_mangle]
pub fn handle_touch(env: JNIEnv, _clz: jclass, event: jobject) {
    if event.is_null() {
        return;
    }

    if let Ok(JValue::Long(p)) = env.get_field(event, "mNativePtr", "J") {
        if p != 0 {
            if let Some(nonptr) =
                std::ptr::NonNull::new(p as usize as *mut ndk_sys::AInputEvent)
            {
                let ev = unsafe { ndk::event::MotionEvent::from_ptr(nonptr) };
                input::handle_touch(ev);
                return;
            }
        }
    }

    // Safe JNI fallback for Android 14-16+ if MotionEvent.mNativePtr is restricted or zero
    let action = env
        .call_method(event, "getActionMasked", "()I", &[])
        .and_then(|v| v.i())
        .unwrap_or(-1);
    if action < 0 {
        return;
    }
    let idx = env
        .call_method(event, "getActionIndex", "()I", &[])
        .and_then(|v| v.i())
        .unwrap_or(0);
    let pointer_id = env
        .call_method(event, "getPointerId", "(I)I", &[JValue::Int(idx)])
        .and_then(|v| v.i())
        .unwrap_or(0);
    let x = env
        .call_method(event, "getX", "(I)F", &[JValue::Int(idx)])
        .and_then(|v| v.f())
        .unwrap_or(0.0);
    let y = env
        .call_method(event, "getY", "(I)F", &[JValue::Int(idx)])
        .and_then(|v| v.f())
        .unwrap_or(0.0);
    let pressure = env
        .call_method(event, "getPressure", "(I)F", &[JValue::Int(idx)])
        .and_then(|v| v.f())
        .unwrap_or(1.0);

    input::handle_touch_event(action, pointer_id, x, y, pressure);
}

pub fn send_key_code(_env: JNIEnv, _clz: jclass, keycode: jint) {
    debug!("send key code!");
    input::send_key_code(keycode);
}

unsafe fn register_natives(jvm: &JavaVM, class_name: &str, methods: &[NativeMethod]) -> jint {
    let env: JNIEnv = match jvm.get_env() {
        Ok(e) => e,
        Err(_) => return JNI_ERR,
    };
    let jni_version = match env.get_version() {
        Ok(v) => v,
        Err(_) => return JNI_ERR,
    };
    let version: jint = jni_version.into();

    debug!("JNI Version : {:#?} ", jni_version);

    let clazz = match env.find_class(class_name) {
        Ok(clazz) => clazz,
        Err(e) => {
            error!("java class not found : {:?}", e);
            return JNI_ERR;
        }
    };
    debug!("clazz: {:#?}", clazz);

    let result = env.register_native_methods(clazz, methods);

    if result.is_ok() {
        debug!("register_natives : succeed");
        version
    } else {
        error!("register_natives : failed ");
        JNI_ERR
    }
}

#[no_mangle]
#[allow(non_snake_case)]
unsafe fn JNI_OnLoad(jvm: JavaVM, _reserved: *mut c_void) -> jint {
    android_logger::init_once(
        Config::default()
            .with_min_level(Level::Info)
            .with_tag("CLIENT_EGL"),
    );

    seccomp::install_sigsys_handler();
    debug!("JNI_OnLoad");

    let class_name: &str = "io/twoyi/Renderer";
    let jni_methods = [
        jni_method!(
            init,
            renderer_init,
            "(Landroid/view/Surface;Ljava/lang/String;Ljava/lang/String;FFI)V"
        ),
        jni_method!(
            resetWindow,
            renderer_reset_window,
            "(Landroid/view/Surface;IIII)V"
        ),
        jni_method!(
            removeWindow,
            renderer_remove_window,
            "(Landroid/view/Surface;)V"
        ),
        jni_method!(handleTouch, handle_touch, "(Landroid/view/MotionEvent;)V"),
        jni_method!(sendKeycode, send_key_code, "(I)V"),
    ];

    register_natives(&jvm, class_name, jni_methods.as_ref())
}
