use cabinet_core::CabinetCore;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::jstring;

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_dashboardJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
) -> jstring {
    run_string(&mut env, database_path, |core| core.dashboard_json())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_searchJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    query: JString,
) -> jstring {
    let query_value = match env.get_string(&query) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    run_string(&mut env, database_path, |core| {
        core.search_json(&query_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_modeJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    mode: JString,
) -> jstring {
    let mode_value = match env.get_string(&mode) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    run_string(&mut env, database_path, |core| core.mode_json(&mode_value))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_registerUrlJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    url: JString,
    title: JString,
    note: JString,
) -> jstring {
    let url_value = match env.get_string(&url) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    let title_value = match env.get_string(&title) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    let note_value = match env.get_string(&note) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    run_string(&mut env, database_path, |core| {
        core.register_url_json(&url_value, &title_value, &note_value)
    })
}

fn run_string(
    env: &mut JNIEnv,
    database_path: JString,
    block: impl FnOnce(CabinetCore) -> cabinet_core::CabinetResult<String>,
) -> jstring {
    let path = match env.get_string(&database_path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(env, &error_json(error.to_string())),
    };
    match CabinetCore::open(path).and_then(block) {
        Ok(value) => jstring_from(env, &value),
        Err(error) => jstring_from(env, &error_json(error.to_string())),
    }
}

fn jstring_from(env: &mut JNIEnv, value: &str) -> jstring {
    env.new_string(value)
        .expect("failed to allocate JVM string")
        .into_raw()
}

fn error_json(message: String) -> String {
    serde_json::json!({
        "error": message,
    })
    .to_string()
}
