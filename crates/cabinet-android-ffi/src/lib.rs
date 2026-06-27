use cabinet_core::CabinetCore;
use jni::JNIEnv;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};

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
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_itemDetailJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
) -> jstring {
    let item_id_value = match env.get_string(&item_id) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    run_string(&mut env, database_path, |core| {
        core.item_detail_json(&item_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_processPreviewQueueJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    limit: i64,
) -> jstring {
    run_string(&mut env, database_path, |core| {
        core.process_preview_queue_json(limit)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_settingsJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
) -> jstring {
    run_string(&mut env, database_path, |core| core.settings_json())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_updateStorageProviderJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    provider_id: JString,
    account_name: JString,
    connection_status: JString,
) -> jstring {
    let provider_id_value = match jstring_value(&mut env, &provider_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let account_name_value = match jstring_value(&mut env, &account_name) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let connection_status_value = match jstring_value(&mut env, &connection_status) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.update_storage_provider_json(
            &provider_id_value,
            &account_name_value,
            &connection_status_value,
        )
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_eventsJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    limit: i64,
) -> jstring {
    run_string(&mut env, database_path, |core| core.events_json(limit))
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_backupExportJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
) -> jstring {
    run_string(&mut env, database_path, |core| core.backup_export_json())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_backupImportJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    backup_json: JString,
) -> jstring {
    let backup_json_value = match jstring_value(&mut env, &backup_json) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.backup_import_json(&backup_json_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_duplicateReportJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
) -> jstring {
    run_string(&mut env, database_path, |core| core.duplicate_report_json())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_updateItemFlagsJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    is_favorite: jboolean,
    is_unsorted: jboolean,
) -> jstring {
    let item_id_value = match env.get_string(&item_id) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    run_string(&mut env, database_path, |core| {
        core.update_item_flags_json(&item_id_value, is_favorite != 0, is_unsorted != 0)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_markItemOpenedJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.mark_item_opened_json(&item_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_addTagToItemJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    tag_name: JString,
    color: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let tag_name_value = match jstring_value(&mut env, &tag_name) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let color_value = match jstring_value(&mut env, &color) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.add_tag_to_item_json(&item_id_value, &tag_name_value, &color_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_removeTagFromItemJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    tag_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let tag_id_value = match jstring_value(&mut env, &tag_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.remove_tag_from_item_json(&item_id_value, &tag_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_addItemToCollectionJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    collection_title: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let collection_title_value = match jstring_value(&mut env, &collection_title) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.add_item_to_collection_json(&item_id_value, &collection_title_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_removeItemFromCollectionJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    collection_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let collection_id_value = match jstring_value(&mut env, &collection_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.remove_item_from_collection_json(&item_id_value, &collection_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_moveItemToTrashJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.move_item_to_trash_json(&item_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_restoreItemFromTrashJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.restore_item_from_trash_json(&item_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_deleteItemPermanentlyJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.delete_item_permanently_json(&item_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_duplicateItemJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.duplicate_item_json(&item_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_renameItemJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    new_display_name: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let new_display_name_value = match jstring_value(&mut env, &new_display_name) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.rename_item_json(&item_id_value, &new_display_name_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_addVersionJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    path: JString,
    display_name: JString,
    mime_type: JString,
    size: i64,
    note: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let path_value = match jstring_value(&mut env, &path) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let display_name_value = match jstring_value(&mut env, &display_name) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let mime_type_value = match jstring_value(&mut env, &mime_type) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let note_value = match jstring_value(&mut env, &note) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.add_version_json(
            &item_id_value,
            &path_value,
            &display_name_value,
            &mime_type_value,
            size,
            &note_value,
        )
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_setCurrentVersionJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    version_id: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let version_id_value = match jstring_value(&mut env, &version_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.set_current_version_json(&item_id_value, &version_id_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_addReferenceJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    reference_type: JString,
    source_app: JString,
    source_id: JString,
    title: JString,
    uri: JString,
    note: JString,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let reference_type_value = match jstring_value(&mut env, &reference_type) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let source_app_value = match jstring_value(&mut env, &source_app) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let source_id_value = match jstring_value(&mut env, &source_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let title_value = match jstring_value(&mut env, &title) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let uri_value = match jstring_value(&mut env, &uri) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let note_value = match jstring_value(&mut env, &note) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.add_reference_json(
            &item_id_value,
            &reference_type_value,
            &source_app_value,
            &source_id_value,
            &title_value,
            &uri_value,
            &note_value,
        )
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_addMemoJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    body: JString,
    is_protected: jboolean,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    let body_value = match jstring_value(&mut env, &body) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.add_memo_json(&item_id_value, &body_value, is_protected != 0)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_setSecurityPinJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    pin: JString,
) -> jstring {
    let pin_value = match jstring_value(&mut env, &pin) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.set_security_pin_json(&pin_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_verifySecurityPinJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    pin: JString,
) -> jstring {
    let pin_value = match jstring_value(&mut env, &pin) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.verify_security_pin_json(&pin_value)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_setItemProtectedJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    item_id: JString,
    is_protected: jboolean,
) -> jstring {
    let item_id_value = match jstring_value(&mut env, &item_id) {
        Ok(value) => value,
        Err(error) => return error,
    };
    run_string(&mut env, database_path, |core| {
        core.set_item_protected_json(&item_id_value, is_protected != 0)
    })
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

#[unsafe(no_mangle)]
pub extern "system" fn Java_jp_viastrasse_cabinetstrasse_core_CabinetNative_registerFileJson(
    mut env: JNIEnv,
    _class: JClass,
    database_path: JString,
    path: JString,
    display_name: JString,
    mime_type: JString,
    size: i64,
    source_kind: JString,
    note: JString,
) -> jstring {
    let path_value = match env.get_string(&path) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    let display_name_value = match env.get_string(&display_name) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    let mime_type_value = match env.get_string(&mime_type) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    let source_kind_value = match env.get_string(&source_kind) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    let note_value = match env.get_string(&note) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(error) => return jstring_from(&mut env, &error_json(error.to_string())),
    };
    run_string(&mut env, database_path, |core| {
        core.register_file_json(
            &path_value,
            &display_name_value,
            &mime_type_value,
            size,
            &source_kind_value,
            &note_value,
        )
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

fn jstring_value(env: &mut JNIEnv, value: &JString) -> Result<String, jstring> {
    match env.get_string(value) {
        Ok(value) => Ok(value.to_string_lossy().into_owned()),
        Err(error) => Err(jstring_from(env, &error_json(error.to_string()))),
    }
}

fn error_json(message: String) -> String {
    serde_json::json!({
        "error": message,
    })
    .to_string()
}
