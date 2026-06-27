use rusqlite::types::{Value as SqlValue, ValueRef};
use rusqlite::{Connection, OptionalExtension, params, params_from_iter};
use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::Read;
use std::path::{Path, PathBuf};
use time::OffsetDateTime;
use uuid::Uuid;
use zip::read::ZipArchive;

const SCHEMA_VERSION: i64 = 1;
const BACKUP_TABLES: &[(&str, &[&str])] = &[
    (
        "storage_provider_accounts",
        &[
            "id",
            "provider_type",
            "display_name",
            "account_name",
            "auth_type",
            "connection_status",
            "created_at",
            "updated_at",
            "last_connected_at",
        ],
    ),
    (
        "remote_file_references",
        &[
            "id",
            "provider_account_id",
            "remote_file_id",
            "remote_path",
            "display_name",
            "mime_type",
            "size",
            "modified_at",
            "etag",
            "web_url",
            "is_cached",
            "cached_file_path",
            "last_synced_at",
        ],
    ),
    (
        "cabinet_files",
        &[
            "id",
            "path",
            "original_file_name",
            "mime_type",
            "size",
            "hash",
            "created_at",
            "updated_at",
            "storage_type",
        ],
    ),
    (
        "cabinet_documents",
        &[
            "id",
            "title",
            "description",
            "current_version_id",
            "created_at",
            "updated_at",
            "status",
        ],
    ),
    (
        "cabinet_versions",
        &[
            "id",
            "document_id",
            "version_number",
            "file_id",
            "display_name",
            "original_file_name",
            "registered_at",
            "source_app",
            "source_id",
            "note",
            "is_current",
        ],
    ),
    (
        "cabinet_items",
        &[
            "id",
            "file_id",
            "document_id",
            "remote_file_reference_id",
            "title",
            "display_name",
            "mime_type",
            "path",
            "source_kind",
            "size",
            "hash",
            "created_at",
            "updated_at",
            "last_opened_at",
            "is_favorite",
            "is_archived",
            "is_unsorted",
            "note",
        ],
    ),
    ("cabinet_tags", &["id", "name", "color", "created_at"]),
    ("cabinet_item_tags", &["item_id", "tag_id"]),
    (
        "cabinet_collections",
        &["id", "title", "description", "created_at", "updated_at"],
    ),
    (
        "cabinet_collection_items",
        &["collection_id", "item_id", "added_at"],
    ),
    (
        "cabinet_references",
        &[
            "id",
            "item_id",
            "reference_type",
            "source_app",
            "source_id",
            "title",
            "uri",
            "note",
            "created_at",
        ],
    ),
    (
        "cabinet_previews",
        &[
            "id",
            "item_id",
            "preview_type",
            "title",
            "summary_text",
            "thumbnail_path",
            "extracted_text",
            "page_count",
            "duration",
            "width",
            "height",
            "generated_at",
            "status",
        ],
    ),
    (
        "cabinet_memos",
        &[
            "id",
            "item_id",
            "body",
            "is_protected",
            "created_at",
            "updated_at",
        ],
    ),
    (
        "cabinet_item_security",
        &[
            "item_id",
            "is_protected",
            "hidden_when_locked",
            "encrypted_hint",
            "updated_at",
        ],
    ),
    ("security_settings", &["key", "value", "updated_at"]),
    (
        "cabinet_events",
        &["id", "event_type", "item_id", "payload_json", "created_at"],
    ),
    (
        "smart_folders",
        &[
            "id",
            "title",
            "condition_sql",
            "display_condition",
            "created_at",
        ],
    ),
];

#[derive(Debug)]
pub enum CabinetError {
    Sqlite(rusqlite::Error),
    Json(serde_json::Error),
    Message(String),
}

impl std::fmt::Display for CabinetError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Sqlite(error) => write!(f, "{error}"),
            Self::Json(error) => write!(f, "{error}"),
            Self::Message(error) => write!(f, "{error}"),
        }
    }
}

impl std::error::Error for CabinetError {}

impl From<rusqlite::Error> for CabinetError {
    fn from(value: rusqlite::Error) -> Self {
        Self::Sqlite(value)
    }
}

impl From<serde_json::Error> for CabinetError {
    fn from(value: serde_json::Error) -> Self {
        Self::Json(value)
    }
}

pub type CabinetResult<T> = Result<T, CabinetError>;

#[derive(Serialize)]
pub struct CabinetDashboard {
    pub version: i64,
    pub explorer_count: i64,
    pub library_count: i64,
    pub collection_count: i64,
    pub inbox_count: i64,
    pub favorite_count: i64,
    pub trash_count: i64,
    pub recent_items: Vec<CabinetItemSummary>,
    pub collections: Vec<CabinetCollectionSummary>,
    pub smart_folders: Vec<SmartFolderSummary>,
}

#[derive(Serialize)]
pub struct CabinetItemSummary {
    pub id: String,
    pub title: String,
    pub display_name: String,
    pub mime_type: String,
    pub source_kind: String,
    pub size: i64,
    pub is_favorite: bool,
    pub is_unsorted: bool,
    pub summary_text: String,
    pub updated_at: String,
}

#[derive(Serialize)]
pub struct CabinetCollectionSummary {
    pub id: String,
    pub title: String,
    pub item_count: i64,
}

#[derive(Serialize)]
pub struct SmartFolderSummary {
    pub id: String,
    pub title: String,
    pub condition: String,
    pub item_count: i64,
}

#[derive(Serialize)]
pub struct SearchResponse {
    pub query: String,
    pub results: Vec<CabinetItemSummary>,
}

#[derive(Serialize)]
pub struct ModeResponse {
    pub mode: String,
    pub title: String,
    pub items: Vec<CabinetItemSummary>,
    pub collections: Vec<CabinetCollectionSummary>,
    pub smart_folders: Vec<SmartFolderSummary>,
}

#[derive(Serialize)]
pub struct CabinetItemDetail {
    pub item: CabinetItemSummary,
    pub path: String,
    pub hash: String,
    pub note: String,
    pub tags: Vec<TagSummary>,
    pub collections: Vec<CabinetCollectionSummary>,
    pub previews: Vec<CabinetPreviewSummary>,
    pub references: Vec<CabinetReferenceSummary>,
    pub versions: Vec<CabinetVersionSummary>,
}

#[derive(Serialize)]
pub struct CabinetPreviewSummary {
    pub id: String,
    pub preview_type: String,
    pub title: String,
    pub summary_text: String,
    pub status: String,
    pub generated_at: String,
}

#[derive(Serialize)]
pub struct CabinetReferenceSummary {
    pub id: String,
    pub reference_type: String,
    pub source_app: String,
    pub title: String,
    pub uri: String,
    pub note: String,
}

#[derive(Serialize)]
pub struct CabinetVersionSummary {
    pub id: String,
    pub version_number: i64,
    pub display_name: String,
    pub note: String,
    pub is_current: bool,
}

#[derive(Serialize)]
pub struct PreviewProcessReport {
    pub processed: i64,
    pub remaining: i64,
}

#[derive(Serialize)]
pub struct SettingsSnapshot {
    pub providers: Vec<StorageProviderAccountSummary>,
    pub backup: BackupSummary,
    pub security: SecuritySummary,
}

#[derive(Serialize)]
pub struct StorageProviderAccountSummary {
    pub id: String,
    pub provider_type: String,
    pub display_name: String,
    pub account_name: String,
    pub auth_type: String,
    pub connection_status: String,
    pub last_connected_at: String,
}

#[derive(Serialize)]
pub struct BackupSummary {
    pub item_count: i64,
    pub collection_count: i64,
    pub tag_count: i64,
    pub preview_count: i64,
    pub exported_at: String,
}

#[derive(Serialize)]
pub struct BackupImportReport {
    pub restored_at: String,
    pub item_count: i64,
    pub collection_count: i64,
    pub tag_count: i64,
    pub preview_count: i64,
}

#[derive(Serialize)]
pub struct SecuritySummary {
    pub pin_enabled: bool,
    pub protected_item_count: i64,
    pub protected_memo_count: i64,
}

#[derive(Serialize)]
pub struct SecurityCheckResult {
    pub verified: bool,
}

#[derive(Deserialize)]
struct BackupEnvelope {
    app: String,
    schema_version: i64,
    tables: Map<String, Value>,
}

#[derive(Serialize)]
pub struct DuplicateReport {
    pub groups: Vec<DuplicateGroup>,
}

#[derive(Serialize)]
pub struct DuplicateGroup {
    pub hash: String,
    pub size: i64,
    pub items: Vec<CabinetItemSummary>,
}

#[derive(Serialize)]
pub struct EditOptions {
    pub tags: Vec<TagSummary>,
    pub collections: Vec<CabinetCollectionSummary>,
}

#[derive(Serialize)]
pub struct TagSummary {
    pub id: String,
    pub name: String,
    pub color: String,
}

struct GeneratedPreview {
    summary_text: String,
    width: Option<i64>,
    height: Option<i64>,
    status: &'static str,
}

pub struct CabinetCore {
    conn: Connection,
}

impl CabinetCore {
    pub fn open(path: impl AsRef<Path>) -> CabinetResult<Self> {
        let conn = Connection::open(path)?;
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "busy_timeout", 5000)?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        let core = Self { conn };
        core.migrate()?;
        core.seed_reference_data()?;
        Ok(core)
    }

    pub fn dashboard_json(&self) -> CabinetResult<String> {
        let dashboard = self.dashboard()?;
        Ok(serde_json::to_string(&dashboard)?)
    }

    pub fn search_json(&self, query: &str) -> CabinetResult<String> {
        let response = SearchResponse {
            query: query.to_owned(),
            results: self.search(query)?,
        };
        Ok(serde_json::to_string(&response)?)
    }

    pub fn mode_json(&self, mode: &str) -> CabinetResult<String> {
        let normalized = mode.trim().to_ascii_lowercase();
        let response = match normalized.as_str() {
            "explorer" => ModeResponse {
                mode: "Explorer".to_owned(),
                title: "Explorer".to_owned(),
                items: self.query_items(
                    "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                     FROM cabinet_items
                     WHERE source_kind IN ('local', 'mail') AND is_archived = 0
                     ORDER BY updated_at DESC, title ASC
                     LIMIT 100",
                    [],
                )?,
                collections: Vec::new(),
                smart_folders: Vec::new(),
            },
            "library" => ModeResponse {
                mode: "Library".to_owned(),
                title: "Library".to_owned(),
                items: self.query_items(
                    "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                     FROM cabinet_items
                     WHERE is_archived = 0
                     ORDER BY mime_type ASC, title ASC
                     LIMIT 100",
                    [],
                )?,
                collections: Vec::new(),
                smart_folders: Vec::new(),
            },
            "collection" => ModeResponse {
                mode: "Collection".to_owned(),
                title: "Collection".to_owned(),
                items: Vec::new(),
                collections: self.collections()?,
                smart_folders: Vec::new(),
            },
            "inbox" => ModeResponse {
                mode: "Inbox".to_owned(),
                title: "Inbox".to_owned(),
                items: self.query_items(
                    "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                     FROM cabinet_items
                     WHERE is_unsorted = 1 AND is_archived = 0
                     ORDER BY updated_at DESC, title ASC
                     LIMIT 100",
                    [],
                )?,
                collections: Vec::new(),
                smart_folders: Vec::new(),
            },
            "smart folder" | "smartfolder" | "settings" => ModeResponse {
                mode: mode.to_owned(),
                title: mode.to_owned(),
                items: Vec::new(),
                collections: Vec::new(),
                smart_folders: self.smart_folders()?,
            },
            "trash" | "ゴミ箱" => ModeResponse {
                mode: "Trash".to_owned(),
                title: "Trash".to_owned(),
                items: self.query_items(
                    "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                     FROM cabinet_items
                     WHERE is_archived = 1
                     ORDER BY updated_at DESC, title ASC
                     LIMIT 100",
                    [],
                )?,
                collections: Vec::new(),
                smart_folders: Vec::new(),
            },
            _ => ModeResponse {
                mode: "Search".to_owned(),
                title: "Search".to_owned(),
                items: self.search("")?,
                collections: Vec::new(),
                smart_folders: self.smart_folders()?,
            },
        };
        Ok(serde_json::to_string(&response)?)
    }

    pub fn item_detail_json(&self, item_id: &str) -> CabinetResult<String> {
        let item = self
            .item_by_id(item_id)?
            .ok_or_else(|| CabinetError::Message(format!("Item not found: {item_id}")))?;
        let (path, hash, note, document_id): (String, String, String, Option<String>) =
            self.conn.query_row(
                "SELECT path, hash, note, document_id FROM cabinet_items WHERE id = ?1",
                params![item_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )?;
        let detail = CabinetItemDetail {
            item,
            path,
            hash,
            note,
            tags: self.tags_for_item(item_id)?,
            collections: self.collections_for_item(item_id)?,
            previews: self.previews_for_item(item_id)?,
            references: self.references_for_item(item_id)?,
            versions: match document_id {
                Some(id) => self.versions_for_document(&id)?,
                None => Vec::new(),
            },
        };
        Ok(serde_json::to_string(&detail)?)
    }

    pub fn process_preview_queue_json(&self, limit: i64) -> CabinetResult<String> {
        let mut stmt = self.conn.prepare(
            "SELECT p.id, p.item_id, i.path, i.mime_type, i.display_name
             FROM cabinet_previews p
             JOIN cabinet_items i ON i.id = p.item_id
             WHERE p.status = 'queued'
             ORDER BY p.generated_at ASC
             LIMIT ?1",
        )?;
        let rows = stmt.query_map(params![limit], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, String>(3)?,
                row.get::<_, String>(4)?,
            ))
        })?;
        let mut queue = Vec::new();
        for row in rows {
            queue.push(row?);
        }
        drop(stmt);

        let mut processed = 0_i64;
        for (preview_id, item_id, path, mime_type, display_name) in queue {
            let preview = preview_for_file(&path, &mime_type, &display_name);
            self.conn.execute(
                "UPDATE cabinet_previews
                 SET summary_text = ?1,
                     extracted_text = ?1,
                     width = ?2,
                     height = ?3,
                     generated_at = ?4,
                     status = ?5
                 WHERE id = ?6",
                params![
                    &preview.summary_text,
                    preview.width,
                    preview.height,
                    now_string(),
                    preview.status,
                    preview_id
                ],
            )?;
            self.rebuild_fts_for_item(&item_id)?;
            self.log_event(
                "Cabinet.PreviewGenerated",
                Some(&item_id),
                serde_json::json!({
                    "preview_id": preview_id,
                    "mime_type": mime_type,
                    "status": preview.status,
                }),
            )?;
            processed += 1;
        }

        let remaining =
            self.scalar("SELECT COUNT(*) FROM cabinet_previews WHERE status = 'queued'")?;
        Ok(serde_json::to_string(&PreviewProcessReport {
            processed,
            remaining,
        })?)
    }

    pub fn settings_json(&self) -> CabinetResult<String> {
        let snapshot = SettingsSnapshot {
            providers: self.storage_provider_accounts()?,
            backup: self.backup_summary()?,
            security: self.security_summary()?,
        };
        Ok(serde_json::to_string(&snapshot)?)
    }

    pub fn update_storage_provider_json(
        &self,
        provider_id: &str,
        account_name: &str,
        connection_status: &str,
    ) -> CabinetResult<String> {
        let status = match connection_status.trim() {
            "connected" | "not_configured" | "error" | "offline" => connection_status.trim(),
            _ => "not_configured",
        };
        let now = now_string();
        let updated = self.conn.execute(
            "UPDATE storage_provider_accounts
             SET account_name = ?1, connection_status = ?2, updated_at = ?3,
                 last_connected_at = CASE WHEN ?2 = 'connected' THEN ?3 ELSE last_connected_at END
             WHERE id = ?4",
            params![account_name.trim(), status, now, provider_id],
        )?;
        if updated == 0 {
            return Err(CabinetError::Message(format!(
                "Storage Provider not found: {provider_id}"
            )));
        }
        self.log_event(
            "Cabinet.StorageProviderUpdated",
            None,
            serde_json::json!({
                "provider_id": provider_id,
                "connection_status": status,
            }),
        )?;
        self.settings_json()
    }

    pub fn events_json(&self, limit: i64) -> CabinetResult<String> {
        let mut stmt = self.conn.prepare(
            "SELECT id, event_type, item_id, payload_json, created_at
             FROM cabinet_events
             ORDER BY created_at DESC
             LIMIT ?1",
        )?;
        let rows = stmt.query_map(params![limit], |row| {
            Ok(serde_json::json!({
                "id": row.get::<_, String>(0)?,
                "event_type": row.get::<_, String>(1)?,
                "item_id": row.get::<_, Option<String>>(2)?,
                "payload": serde_json::from_str::<Value>(&row.get::<_, String>(3)?).unwrap_or(Value::Null),
                "created_at": row.get::<_, String>(4)?,
            }))
        })?;
        let mut events = Vec::new();
        for row in rows {
            events.push(row?);
        }
        Ok(serde_json::json!({ "events": events }).to_string())
    }

    pub fn backup_export_json(&self) -> CabinetResult<String> {
        let tables = self.backup_tables_json()?;
        let value = serde_json::json!({
            "app": "Cabinet-STRASSE",
            "schema_version": SCHEMA_VERSION,
            "exported_at": now_string(),
            "summary": self.backup_summary()?,
            "items": self.search("")?,
            "collections": self.collections()?,
            "smart_folders": self.smart_folders()?,
            "providers": self.storage_provider_accounts()?,
            "tables": tables,
        });
        Ok(serde_json::to_string_pretty(&value)?)
    }

    pub fn backup_import_json(&self, backup_json: &str) -> CabinetResult<String> {
        let backup: BackupEnvelope = serde_json::from_str(backup_json)?;
        if backup.app != "Cabinet-STRASSE" {
            return Err(CabinetError::Message(
                "Backup is not for Cabinet-STRASSE.".to_owned(),
            ));
        }
        if backup.schema_version > SCHEMA_VERSION {
            return Err(CabinetError::Message(format!(
                "Backup schema version {} is newer than supported schema {}.",
                backup.schema_version, SCHEMA_VERSION
            )));
        }
        if backup.tables.is_empty() {
            return Err(CabinetError::Message(
                "Backup does not contain restorable table data.".to_owned(),
            ));
        }

        self.conn.execute_batch("BEGIN IMMEDIATE TRANSACTION")?;
        let result = self.restore_backup_tables(&backup.tables);
        if let Err(error) = result {
            let _ = self.conn.execute_batch("ROLLBACK");
            return Err(error);
        }
        self.conn.execute_batch("COMMIT")?;
        self.rebuild_fts()?;
        self.seed_reference_data()?;

        let report = BackupImportReport {
            restored_at: now_string(),
            item_count: self.scalar("SELECT COUNT(*) FROM cabinet_items")?,
            collection_count: self.scalar("SELECT COUNT(*) FROM cabinet_collections")?,
            tag_count: self.scalar("SELECT COUNT(*) FROM cabinet_tags")?,
            preview_count: self.scalar("SELECT COUNT(*) FROM cabinet_previews")?,
        };
        Ok(serde_json::to_string(&report)?)
    }

    pub fn update_item_flags_json(
        &self,
        item_id: &str,
        is_favorite: bool,
        is_unsorted: bool,
    ) -> CabinetResult<String> {
        let updated_at = now_string();
        let changed = self.conn.execute(
            "UPDATE cabinet_items
             SET is_favorite = ?1, is_unsorted = ?2, updated_at = ?3
             WHERE id = ?4",
            params![
                if is_favorite { 1 } else { 0 },
                if is_unsorted { 1 } else { 0 },
                updated_at,
                item_id
            ],
        )?;
        if changed == 0 {
            return Err(CabinetError::Message(format!("Item not found: {item_id}")));
        }
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.ItemUpdated",
            Some(item_id),
            serde_json::json!({
                "is_favorite": is_favorite,
                "is_unsorted": is_unsorted,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn edit_options_json(&self) -> CabinetResult<String> {
        Ok(serde_json::to_string(&EditOptions {
            tags: self.tags()?,
            collections: self.collections()?,
        })?)
    }

    pub fn duplicate_report_json(&self) -> CabinetResult<String> {
        Ok(serde_json::to_string(&DuplicateReport {
            groups: self.duplicate_groups()?,
        })?)
    }

    pub fn add_tag_to_item_json(
        &self,
        item_id: &str,
        tag_name: &str,
        color: &str,
    ) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        let normalized = tag_name.trim();
        if normalized.is_empty() {
            return Err(CabinetError::Message("Tag name is empty.".to_owned()));
        }
        let tag_id = self
            .conn
            .query_row(
                "SELECT id FROM cabinet_tags WHERE name = ?1",
                params![normalized],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .unwrap_or_else(new_id);
        self.conn.execute(
            "INSERT OR IGNORE INTO cabinet_tags(id, name, color, created_at) VALUES (?1, ?2, ?3, ?4)",
            params![tag_id, normalized, color, now],
        )?;
        self.conn.execute(
            "INSERT OR IGNORE INTO cabinet_item_tags(item_id, tag_id) VALUES (?1, ?2)",
            params![item_id, tag_id],
        )?;
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.TagAdded",
            Some(item_id),
            serde_json::json!({
                "tag_id": tag_id,
                "tag_name": normalized,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn add_item_to_collection_json(
        &self,
        item_id: &str,
        collection_title: &str,
    ) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        let normalized = collection_title.trim();
        if normalized.is_empty() {
            return Err(CabinetError::Message(
                "Collection title is empty.".to_owned(),
            ));
        }
        let collection_id = self
            .conn
            .query_row(
                "SELECT id FROM cabinet_collections WHERE title = ?1",
                params![normalized],
                |row| row.get::<_, String>(0),
            )
            .optional()?
            .unwrap_or_else(new_id);
        self.conn.execute(
            "INSERT OR IGNORE INTO cabinet_collections(id, title, description, created_at, updated_at)
             VALUES (?1, ?2, '', ?3, ?3)",
            params![collection_id, normalized, now],
        )?;
        self.conn.execute(
            "INSERT OR IGNORE INTO cabinet_collection_items(collection_id, item_id, added_at) VALUES (?1, ?2, ?3)",
            params![collection_id, item_id, now],
        )?;
        self.conn.execute(
            "UPDATE cabinet_collections SET updated_at = ?1 WHERE id = ?2",
            params![now, collection_id],
        )?;
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.CollectionCreated",
            Some(item_id),
            serde_json::json!({
                "collection_id": collection_id,
                "title": normalized,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn add_version_json(
        &self,
        item_id: &str,
        path: &str,
        display_name: &str,
        mime_type: &str,
        size: i64,
        note: &str,
    ) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        let document_id: Option<String> = self.conn.query_row(
            "SELECT document_id FROM cabinet_items WHERE id = ?1",
            params![item_id],
            |row| row.get(0),
        )?;
        let document_id = document_id.ok_or_else(|| {
            CabinetError::Message("This item is not attached to a Cabinet Document.".to_owned())
        })?;
        let next_version: i64 = self.conn.query_row(
            "SELECT COALESCE(MAX(version_number), 0) + 1 FROM cabinet_versions WHERE document_id = ?1",
            params![document_id],
            |row| row.get(0),
        )?;
        let file_id = new_id();
        let version_id = new_id();
        let hash = hash_file(path).unwrap_or_else(|_| hash_text(path));
        self.conn.execute(
            "INSERT INTO cabinet_files(id, path, original_file_name, mime_type, size, hash, created_at, updated_at, storage_type)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7, 'local')",
            params![file_id, path, display_name, mime_type, size, hash, now],
        )?;
        self.conn.execute(
            "UPDATE cabinet_versions SET is_current = 0 WHERE document_id = ?1",
            params![document_id],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_versions(
                id, document_id, version_number, file_id, display_name, original_file_name,
                registered_at, source_app, source_id, note, is_current
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?5, ?6, 'Cabinet-STRASSE', NULL, ?7, 1)",
            params![
                version_id,
                document_id,
                next_version,
                file_id,
                display_name,
                now,
                note
            ],
        )?;
        self.conn.execute(
            "UPDATE cabinet_documents SET current_version_id = ?1, updated_at = ?2 WHERE id = ?3",
            params![version_id, now, document_id],
        )?;
        self.apply_version_to_item(item_id, &version_id, &now)?;
        self.log_event(
            "Cabinet.VersionAdded",
            Some(item_id),
            serde_json::json!({
                "document_id": document_id,
                "version_id": version_id,
                "version_number": next_version,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn set_current_version_json(
        &self,
        item_id: &str,
        version_id: &str,
    ) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        let item_document_id: Option<String> = self.conn.query_row(
            "SELECT document_id FROM cabinet_items WHERE id = ?1",
            params![item_id],
            |row| row.get(0),
        )?;
        let item_document_id = item_document_id.ok_or_else(|| {
            CabinetError::Message("This item is not attached to a Cabinet Document.".to_owned())
        })?;
        let version_document_id: String = self.conn.query_row(
            "SELECT document_id FROM cabinet_versions WHERE id = ?1",
            params![version_id],
            |row| row.get(0),
        )?;
        if item_document_id != version_document_id {
            return Err(CabinetError::Message(
                "Version does not belong to this Cabinet item.".to_owned(),
            ));
        }
        self.apply_version_to_item(item_id, version_id, &now)?;
        self.log_event(
            "Cabinet.VersionUpdated",
            Some(item_id),
            serde_json::json!({
                "document_id": item_document_id,
                "version_id": version_id,
                "is_current": true,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn move_item_to_trash_json(&self, item_id: &str) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        self.conn.execute(
            "UPDATE cabinet_items SET is_archived = 1, is_unsorted = 0, updated_at = ?1 WHERE id = ?2",
            params![now, item_id],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_memos(id, item_id, body, is_protected, created_at, updated_at)
             VALUES (?1, ?2, 'Moved to Cabinet trash', 0, ?3, ?3)",
            params![new_id(), item_id, now],
        )?;
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.ItemDeleted",
            Some(item_id),
            serde_json::json!({
                "trash": true,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn restore_item_from_trash_json(&self, item_id: &str) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        self.conn.execute(
            "UPDATE cabinet_items SET is_archived = 0, updated_at = ?1 WHERE id = ?2",
            params![now, item_id],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_memos(id, item_id, body, is_protected, created_at, updated_at)
             VALUES (?1, ?2, 'Restored from Cabinet trash', 0, ?3, ?3)",
            params![new_id(), item_id, now],
        )?;
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.ItemRestored",
            Some(item_id),
            serde_json::json!({
                "trash": false,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn delete_item_permanently_json(&self, item_id: &str) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let file_paths = self.file_paths_for_item(item_id)?;
        self.conn.execute(
            "DELETE FROM cabinet_fts WHERE item_id = ?1",
            params![item_id],
        )?;
        self.conn
            .execute("DELETE FROM cabinet_items WHERE id = ?1", params![item_id])?;
        for path in file_paths {
            if !path.is_empty() {
                let _ = std::fs::remove_file(path);
            }
        }
        self.log_event(
            "Cabinet.ItemDeleted",
            Some(item_id),
            serde_json::json!({
                "permanent": true,
            }),
        )?;
        Ok(serde_json::json!({
            "deleted": true,
            "item_id": item_id,
        })
        .to_string())
    }

    pub fn add_reference_json(
        &self,
        item_id: &str,
        reference_type: &str,
        source_app: &str,
        source_id: &str,
        title: &str,
        uri: &str,
        note: &str,
    ) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        self.conn.execute(
            "INSERT INTO cabinet_references(id, item_id, reference_type, source_app, source_id, title, uri, note, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
            params![new_id(), item_id, reference_type, source_app, source_id, title, uri, note, now],
        )?;
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.ReferenceAdded",
            Some(item_id),
            serde_json::json!({
                "reference_type": reference_type,
                "source_app": source_app,
                "source_id": source_id,
                "title": title,
                "uri": uri,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn add_memo_json(
        &self,
        item_id: &str,
        body: &str,
        is_protected: bool,
    ) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let normalized = body.trim();
        if normalized.is_empty() {
            return Err(CabinetError::Message("Memo body is empty.".to_owned()));
        }
        let now = now_string();
        self.conn.execute(
            "INSERT INTO cabinet_memos(id, item_id, body, is_protected, created_at, updated_at)
             VALUES (?1, ?2, ?3, ?4, ?5, ?5)",
            params![
                new_id(),
                item_id,
                normalized,
                if is_protected { 1 } else { 0 },
                now
            ],
        )?;
        self.conn.execute(
            "UPDATE cabinet_items SET note = trim(note || char(10) || ?1), updated_at = ?2 WHERE id = ?3",
            params![if is_protected { "[protected memo]" } else { normalized }, now, item_id],
        )?;
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.ItemUpdated",
            Some(item_id),
            serde_json::json!({
                "memo_added": true,
                "is_protected": is_protected,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn set_security_pin_json(&self, pin: &str) -> CabinetResult<String> {
        let normalized = pin.trim();
        if normalized.len() < 4 {
            return Err(CabinetError::Message(
                "PIN must contain at least 4 characters.".to_owned(),
            ));
        }
        let now = now_string();
        let hash = hash_text(normalized);
        self.conn.execute(
            "INSERT INTO security_settings(key, value, updated_at)
             VALUES ('pin_hash', ?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
            params![hash, now],
        )?;
        self.settings_json()
    }

    pub fn verify_security_pin_json(&self, pin: &str) -> CabinetResult<String> {
        let expected: Option<String> = self
            .conn
            .query_row(
                "SELECT value FROM security_settings WHERE key = 'pin_hash'",
                [],
                |row| row.get(0),
            )
            .optional()?;
        let verified = expected
            .map(|hash| hash == hash_text(pin.trim()))
            .unwrap_or(false);
        Ok(serde_json::to_string(&SecurityCheckResult { verified })?)
    }

    pub fn set_item_protected_json(
        &self,
        item_id: &str,
        is_protected: bool,
    ) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        self.conn.execute(
            "INSERT INTO cabinet_item_security(
                item_id, is_protected, hidden_when_locked, encrypted_hint, updated_at
             ) VALUES (?1, ?2, ?2, 'metadata-protected', ?3)
             ON CONFLICT(item_id) DO UPDATE SET
                is_protected = excluded.is_protected,
                hidden_when_locked = excluded.hidden_when_locked,
                updated_at = excluded.updated_at",
            params![item_id, if is_protected { 1 } else { 0 }, now],
        )?;
        self.log_event(
            "Cabinet.ItemUpdated",
            Some(item_id),
            serde_json::json!({
                "is_protected": is_protected,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn duplicate_item_json(&self, item_id: &str) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let now = now_string();
        let (path, display_name, mime_type, source_kind, note): (String, String, String, String, String) =
            self.conn.query_row(
                "SELECT path, display_name, mime_type, source_kind, note FROM cabinet_items WHERE id = ?1",
                params![item_id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?, row.get(4)?)),
            )?;
        let source = PathBuf::from(&path);
        if !source.exists() {
            return Err(CabinetError::Message(
                "Source file does not exist.".to_owned(),
            ));
        }
        let duplicate_path = duplicate_path(&source);
        std::fs::copy(&source, &duplicate_path)
            .map_err(|error| CabinetError::Message(error.to_string()))?;
        let duplicate_name = duplicate_path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("duplicate")
            .to_owned();
        let json = self.register_file_json(
            duplicate_path.to_string_lossy().as_ref(),
            &duplicate_name,
            &mime_type,
            duplicate_path
                .metadata()
                .map(|m| m.len() as i64)
                .unwrap_or_default(),
            &source_kind,
            &format!("Duplicated from {display_name}. {note}"),
        )?;
        self.conn.execute(
            "UPDATE cabinet_items SET updated_at = ?1 WHERE id = ?2",
            params![now, item_id],
        )?;
        Ok(json)
    }

    pub fn rename_item_json(&self, item_id: &str, new_display_name: &str) -> CabinetResult<String> {
        self.ensure_item_exists(item_id)?;
        let normalized = new_display_name.trim();
        if normalized.is_empty() {
            return Err(CabinetError::Message("New name is empty.".to_owned()));
        }
        let (path, file_id): (String, Option<String>) = self.conn.query_row(
            "SELECT path, file_id FROM cabinet_items WHERE id = ?1",
            params![item_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        let source = PathBuf::from(&path);
        let mut destination_path = path.clone();
        if source.exists() {
            let destination = source.with_file_name(normalized);
            std::fs::rename(&source, &destination)
                .map_err(|error| CabinetError::Message(error.to_string()))?;
            destination_path = destination.to_string_lossy().into_owned();
        }
        let now = now_string();
        self.conn.execute(
            "UPDATE cabinet_items
             SET title = ?1, display_name = ?1, path = ?2, updated_at = ?3
             WHERE id = ?4",
            params![normalized, destination_path, now, item_id],
        )?;
        if let Some(file_id) = file_id {
            self.conn.execute(
                "UPDATE cabinet_files SET path = ?1, original_file_name = ?2, updated_at = ?3 WHERE id = ?4",
                params![destination_path, normalized, now, file_id],
            )?;
        }
        self.rebuild_fts_for_item(item_id)?;
        self.log_event(
            "Cabinet.ItemUpdated",
            Some(item_id),
            serde_json::json!({
                "renamed_to": normalized,
            }),
        )?;
        self.item_detail_json(item_id)
    }

    pub fn register_url_json(&self, url: &str, title: &str, note: &str) -> CabinetResult<String> {
        let now = now_string();
        let id = new_id();
        let file_hash = hash_text(url);
        self.conn.execute(
            "INSERT INTO cabinet_items (
                id, file_id, document_id, title, display_name, mime_type, path, source_kind,
                size, hash, created_at, updated_at, last_opened_at, is_favorite, is_archived,
                is_unsorted, note
            ) VALUES (?1, NULL, NULL, ?2, ?3, 'text/uri-list', ?4, 'url', 0, ?5, ?6, ?6, NULL, 0, 0, 1, ?7)",
            params![id, title, title, url, file_hash, now, note],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_previews (
                id, item_id, preview_type, title, summary_text, thumbnail_path, extracted_text,
                page_count, duration, width, height, generated_at, status
            ) VALUES (?1, ?2, 'url', ?3, ?4, NULL, ?4, NULL, NULL, NULL, NULL, ?5, 'ready')",
            params![new_id(), id, title, note, now],
        )?;
        self.rebuild_fts_for_item(&id)?;
        self.log_event(
            "Cabinet.ItemAdded",
            Some(&id),
            serde_json::json!({
                "title": title,
                "source_kind": "url",
                "url": url,
            }),
        )?;
        let item = self.item_by_id(&id)?.expect("inserted item must exist");
        Ok(serde_json::to_string(&item)?)
    }

    pub fn register_file_json(
        &self,
        path: &str,
        display_name: &str,
        mime_type: &str,
        size: i64,
        source_kind: &str,
        note: &str,
    ) -> CabinetResult<String> {
        let now = now_string();
        let file_id = new_id();
        let item_id = new_id();
        let document_id = new_id();
        let version_id = new_id();
        let hash = hash_file(path).unwrap_or_else(|_| hash_text(path));

        self.conn.execute(
            "INSERT INTO cabinet_files (
                id, path, original_file_name, mime_type, size, hash, created_at, updated_at, storage_type
            ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7, 'local')",
            params![file_id, path, display_name, mime_type, size, hash, now],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_documents(id, title, description, current_version_id, created_at, updated_at, status)
             VALUES (?1, ?2, '', ?3, ?4, ?4, 'active')",
            params![document_id, display_name, version_id, now],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_versions (
                id, document_id, version_number, file_id, display_name, original_file_name,
                registered_at, source_app, source_id, note, is_current
            ) VALUES (?1, ?2, 1, ?3, ?4, ?4, ?5, 'Cabinet-STRASSE', NULL, ?6, 1)",
            params![version_id, document_id, file_id, display_name, now, note],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_items (
                id, file_id, document_id, title, display_name, mime_type, path, source_kind,
                size, hash, created_at, updated_at, last_opened_at, is_favorite, is_archived,
                is_unsorted, note
            ) VALUES (?1, ?2, ?3, ?4, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?10, NULL, 0, 0, 1, ?11)",
            params![
                item_id,
                file_id,
                document_id,
                display_name,
                mime_type,
                path,
                source_kind,
                size,
                hash,
                now,
                note
            ],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_previews (
                id, item_id, preview_type, title, summary_text, thumbnail_path, extracted_text,
                page_count, duration, width, height, generated_at, status
            ) VALUES (?1, ?2, ?3, ?4, ?5, NULL, ?5, NULL, NULL, NULL, NULL, ?6, 'queued')",
            params![
                new_id(),
                item_id,
                preview_type_for_mime(mime_type),
                display_name,
                note,
                now
            ],
        )?;
        self.rebuild_fts_for_item(&item_id)?;
        self.log_event(
            "Cabinet.ItemAdded",
            Some(&item_id),
            serde_json::json!({
                "display_name": display_name,
                "mime_type": mime_type,
                "source_kind": source_kind,
                "size": size,
            }),
        )?;
        self.log_event(
            "Cabinet.VersionAdded",
            Some(&item_id),
            serde_json::json!({
                "document_id": document_id,
                "version_id": version_id,
                "version_number": 1,
            }),
        )?;
        let item = self
            .item_by_id(&item_id)?
            .expect("inserted file item must exist");
        Ok(serde_json::to_string(&item)?)
    }

    fn migrate(&self) -> CabinetResult<()> {
        self.conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS schema_info (
                version INTEGER NOT NULL
            );

            CREATE TABLE IF NOT EXISTS cabinet_files (
                id TEXT PRIMARY KEY,
                path TEXT NOT NULL,
                original_file_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                hash TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                storage_type TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS remote_file_references (
                id TEXT PRIMARY KEY,
                provider_account_id TEXT NOT NULL,
                remote_file_id TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size INTEGER NOT NULL DEFAULT 0,
                modified_at TEXT,
                etag TEXT,
                web_url TEXT,
                is_cached INTEGER NOT NULL DEFAULT 0,
                cached_file_path TEXT,
                last_synced_at TEXT
            );

            CREATE TABLE IF NOT EXISTS storage_provider_accounts (
                id TEXT PRIMARY KEY,
                provider_type TEXT NOT NULL,
                display_name TEXT NOT NULL,
                account_name TEXT NOT NULL DEFAULT '',
                auth_type TEXT NOT NULL DEFAULT 'none',
                connection_status TEXT NOT NULL DEFAULT 'not_configured',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_connected_at TEXT NOT NULL DEFAULT ''
            );

            CREATE TABLE IF NOT EXISTS cabinet_documents (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                current_version_id TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                status TEXT NOT NULL DEFAULT 'active'
            );

            CREATE TABLE IF NOT EXISTS cabinet_versions (
                id TEXT PRIMARY KEY,
                document_id TEXT NOT NULL,
                version_number INTEGER NOT NULL,
                file_id TEXT,
                display_name TEXT NOT NULL,
                original_file_name TEXT NOT NULL,
                registered_at TEXT NOT NULL,
                source_app TEXT,
                source_id TEXT,
                note TEXT NOT NULL DEFAULT '',
                is_current INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(document_id) REFERENCES cabinet_documents(id) ON DELETE CASCADE,
                FOREIGN KEY(file_id) REFERENCES cabinet_files(id) ON DELETE SET NULL
            );

            CREATE TABLE IF NOT EXISTS cabinet_items (
                id TEXT PRIMARY KEY,
                file_id TEXT,
                document_id TEXT,
                remote_file_reference_id TEXT,
                title TEXT NOT NULL,
                display_name TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                path TEXT NOT NULL DEFAULT '',
                source_kind TEXT NOT NULL DEFAULT 'local',
                size INTEGER NOT NULL DEFAULT 0,
                hash TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                last_opened_at TEXT,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                is_archived INTEGER NOT NULL DEFAULT 0,
                is_unsorted INTEGER NOT NULL DEFAULT 1,
                note TEXT NOT NULL DEFAULT '',
                FOREIGN KEY(file_id) REFERENCES cabinet_files(id) ON DELETE SET NULL,
                FOREIGN KEY(document_id) REFERENCES cabinet_documents(id) ON DELETE SET NULL,
                FOREIGN KEY(remote_file_reference_id) REFERENCES remote_file_references(id) ON DELETE SET NULL
            );

            CREATE TABLE IF NOT EXISTS cabinet_tags (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL UNIQUE,
                color TEXT NOT NULL DEFAULT '#607D8B',
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS cabinet_item_tags (
                item_id TEXT NOT NULL,
                tag_id TEXT NOT NULL,
                PRIMARY KEY(item_id, tag_id),
                FOREIGN KEY(item_id) REFERENCES cabinet_items(id) ON DELETE CASCADE,
                FOREIGN KEY(tag_id) REFERENCES cabinet_tags(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS cabinet_collections (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS cabinet_collection_items (
                collection_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                added_at TEXT NOT NULL,
                PRIMARY KEY(collection_id, item_id),
                FOREIGN KEY(collection_id) REFERENCES cabinet_collections(id) ON DELETE CASCADE,
                FOREIGN KEY(item_id) REFERENCES cabinet_items(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS cabinet_references (
                id TEXT PRIMARY KEY,
                item_id TEXT NOT NULL,
                reference_type TEXT NOT NULL,
                source_app TEXT NOT NULL,
                source_id TEXT NOT NULL,
                title TEXT NOT NULL,
                uri TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL,
                FOREIGN KEY(item_id) REFERENCES cabinet_items(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS cabinet_previews (
                id TEXT PRIMARY KEY,
                item_id TEXT NOT NULL,
                preview_type TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                summary_text TEXT NOT NULL DEFAULT '',
                thumbnail_path TEXT,
                extracted_text TEXT NOT NULL DEFAULT '',
                page_count INTEGER,
                duration INTEGER,
                width INTEGER,
                height INTEGER,
                generated_at TEXT NOT NULL,
                status TEXT NOT NULL,
                FOREIGN KEY(item_id) REFERENCES cabinet_items(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS cabinet_memos (
                id TEXT PRIMARY KEY,
                item_id TEXT NOT NULL,
                body TEXT NOT NULL,
                is_protected INTEGER NOT NULL DEFAULT 0,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                FOREIGN KEY(item_id) REFERENCES cabinet_items(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS cabinet_item_security (
                item_id TEXT PRIMARY KEY,
                is_protected INTEGER NOT NULL DEFAULT 0,
                hidden_when_locked INTEGER NOT NULL DEFAULT 0,
                encrypted_hint TEXT NOT NULL DEFAULT '',
                updated_at TEXT NOT NULL,
                FOREIGN KEY(item_id) REFERENCES cabinet_items(id) ON DELETE CASCADE
            );

            CREATE TABLE IF NOT EXISTS security_settings (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS cabinet_events (
                id TEXT PRIMARY KEY,
                event_type TEXT NOT NULL,
                item_id TEXT,
                payload_json TEXT NOT NULL DEFAULT '{}',
                created_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS smart_folders (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                condition_sql TEXT NOT NULL,
                display_condition TEXT NOT NULL,
                created_at TEXT NOT NULL
            );

            CREATE VIRTUAL TABLE IF NOT EXISTS cabinet_fts USING fts5(
                item_id UNINDEXED,
                title,
                display_name,
                mime_type,
                tags,
                collections,
                note,
                preview_text,
                references_text,
                tokenize='unicode61'
            );

            CREATE INDEX IF NOT EXISTS idx_cabinet_items_updated ON cabinet_items(updated_at DESC);
            CREATE INDEX IF NOT EXISTS idx_cabinet_items_mime ON cabinet_items(mime_type);
            CREATE INDEX IF NOT EXISTS idx_cabinet_items_unsorted ON cabinet_items(is_unsorted);
            CREATE INDEX IF NOT EXISTS idx_cabinet_versions_document ON cabinet_versions(document_id, version_number DESC);
            CREATE INDEX IF NOT EXISTS idx_cabinet_previews_item ON cabinet_previews(item_id);
            ",
        )?;

        let current: Option<i64> = self
            .conn
            .query_row("SELECT version FROM schema_info LIMIT 1", [], |row| {
                row.get(0)
            })
            .optional()?;
        if current.is_none() {
            self.conn.execute(
                "INSERT INTO schema_info(version) VALUES (?1)",
                params![SCHEMA_VERSION],
            )?;
        }
        Ok(())
    }

    fn seed_reference_data(&self) -> CabinetResult<()> {
        let now = now_string();
        self.ensure_storage_providers(&now)?;

        let count: i64 = self
            .conn
            .query_row("SELECT COUNT(*) FROM cabinet_items", [], |row| row.get(0))?;
        if count > 0 {
            return Ok(());
        }

        let samples = [
            (
                "Cabinet-STRASSE 理想形設計書",
                "cabinet-strasse-design-v2.md",
                "text/markdown",
                "local",
                "Explorer / Library / Collection / Smart Folder / Inbox / Search を統合する基準資料。",
                true,
            ),
            (
                "Azure App Service 調査資料",
                "azure-app-service.pdf",
                "application/pdf",
                "local",
                "プレビュー生成、OCR、Collection、Atelier参照の確認用PDF。",
                false,
            ),
            (
                "Mail 添付 ZIP と保護メモ",
                "customer-attachment.zip",
                "application/zip",
                "mail",
                "PPAP対応のため、元メールとパスワード通知メールを別参照として保持する。",
                false,
            ),
            (
                "Microsoft Learn URL",
                "https://learn.microsoft.com/",
                "text/uri-list",
                "url",
                "URLも資料として扱い、タイトル、説明、サムネイル、メモ、Collectionを保持する。",
                true,
            ),
        ];

        let collection_id = new_id();
        self.conn.execute(
            "INSERT INTO cabinet_collections(id, title, description, created_at, updated_at) VALUES (?1, 'STRASSE設計資料', 'CabinetとSTRASSEファミリーの設計資料', ?2, ?2)",
            params![collection_id, now],
        )?;

        for (idx, sample) in samples.iter().enumerate() {
            let item_id = new_id();
            let path = if sample.3 == "url" {
                sample.1.to_owned()
            } else {
                format!("/virtual/cabinet/{}", sample.1)
            };
            self.conn.execute(
                "INSERT INTO cabinet_items (
                    id, file_id, document_id, title, display_name, mime_type, path, source_kind,
                    size, hash, created_at, updated_at, last_opened_at, is_favorite, is_archived,
                    is_unsorted, note
                ) VALUES (?1, NULL, NULL, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?9, NULL, ?10, 0, ?11, ?12)",
                params![
                    item_id,
                    sample.0,
                    sample.1,
                    sample.2,
                    path,
                    sample.3,
                    1024 * (idx as i64 + 1),
                    hash_text(sample.1),
                    now,
                    if sample.5 { 1 } else { 0 },
                    if idx == 0 { 0 } else { 1 },
                    sample.4,
                ],
            )?;
            self.conn.execute(
                "INSERT INTO cabinet_previews (
                    id, item_id, preview_type, title, summary_text, thumbnail_path, extracted_text,
                    page_count, duration, width, height, generated_at, status
                ) VALUES (?1, ?2, ?3, ?4, ?5, NULL, ?5, NULL, NULL, NULL, NULL, ?6, 'ready')",
                params![
                    new_id(),
                    item_id,
                    preview_type_for_mime(sample.2),
                    sample.0,
                    sample.4,
                    now
                ],
            )?;
            self.conn.execute(
                "INSERT INTO cabinet_collection_items(collection_id, item_id, added_at) VALUES (?1, ?2, ?3)",
                params![collection_id, item_id, now],
            )?;
            self.rebuild_fts_for_item(&item_id)?;
        }

        let folders = [
            ("unsorted", "未整理Inbox", "is_unsorted = 1"),
            ("favorites", "お気に入り", "is_favorite = 1"),
            ("pdf", "PDF", "mime_type = 'application/pdf'"),
            ("url", "URL", "mime_type = 'text/uri-list'"),
        ];
        for (id, title, sql) in folders {
            self.conn.execute(
                "INSERT OR IGNORE INTO smart_folders(id, title, condition_sql, display_condition, created_at) VALUES (?1, ?2, ?3, ?3, ?4)",
                params![id, title, sql, now],
            )?;
        }

        self.ensure_storage_providers(&now)?;

        Ok(())
    }

    fn ensure_storage_providers(&self, now: &str) -> CabinetResult<()> {
        let providers = [
            ("local", "Local Storage", "device"),
            ("usb", "USB Storage", "saf"),
            ("sdcard", "SD Card", "saf"),
            ("google_drive", "Google Drive", "oauth2"),
            ("dropbox", "Dropbox", "oauth2"),
            ("onedrive", "OneDrive", "oauth2"),
            ("box", "Box", "oauth2"),
            ("nextcloud", "Nextcloud", "webdav"),
            ("smb", "SMB / NAS", "password"),
            ("webdav", "WebDAV", "password"),
        ];
        for (provider_type, display_name, auth_type) in providers {
            self.conn.execute(
                "INSERT OR IGNORE INTO storage_provider_accounts(
                    id, provider_type, display_name, account_name, auth_type,
                    connection_status, created_at, updated_at, last_connected_at
                ) VALUES (?1, ?2, ?3, '', ?4, 'not_configured', ?5, ?5, '')",
                params![provider_type, provider_type, display_name, auth_type, now],
            )?;
        }

        Ok(())
    }

    fn dashboard(&self) -> CabinetResult<CabinetDashboard> {
        Ok(CabinetDashboard {
            version: self
                .conn
                .query_row("SELECT version FROM schema_info LIMIT 1", [], |row| row.get(0))?,
            explorer_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE source_kind IN ('local', 'mail') AND is_archived = 0")?,
            library_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE is_archived = 0")?,
            collection_count: self.scalar("SELECT COUNT(*) FROM cabinet_collections")?,
            inbox_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE is_unsorted = 1 AND is_archived = 0")?,
            favorite_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE is_favorite = 1 AND is_archived = 0")?,
            trash_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE is_archived = 1")?,
            recent_items: self.query_items(
                "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                 FROM cabinet_items
                 WHERE is_archived = 0
                 ORDER BY updated_at DESC, title ASC
                 LIMIT 12",
                [],
            )?,
            collections: self.collections()?,
            smart_folders: self.smart_folders()?,
        })
    }

    fn search(&self, query: &str) -> CabinetResult<Vec<CabinetItemSummary>> {
        let trimmed = query.trim();
        if trimmed.is_empty() {
            return self.query_items(
                "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                 FROM cabinet_items
                 WHERE is_archived = 0
                 ORDER BY updated_at DESC, title ASC
                 LIMIT 50",
                [],
            );
        }

        let fts_query = trimmed
            .split_whitespace()
            .map(|part| format!("{}*", escape_fts_token(part)))
            .collect::<Vec<_>>()
            .join(" ");
        let mut stmt = self.conn.prepare(
            "SELECT i.id, i.title, i.display_name, i.mime_type, i.source_kind, i.size,
                    i.is_favorite, i.is_unsorted, i.note, i.updated_at
             FROM cabinet_fts f
             JOIN cabinet_items i ON i.id = f.item_id
             WHERE cabinet_fts MATCH ?1 AND i.is_archived = 0
             ORDER BY rank
             LIMIT 50",
        )?;
        let rows = stmt.query_map(params![fts_query], read_item_summary)?;
        let mut items = Vec::new();
        for row in rows {
            items.push(row?);
        }
        Ok(items)
    }

    fn collections(&self) -> CabinetResult<Vec<CabinetCollectionSummary>> {
        let mut stmt = self.conn.prepare(
            "SELECT c.id, c.title, COUNT(ci.item_id)
             FROM cabinet_collections c
             LEFT JOIN cabinet_collection_items ci ON ci.collection_id = c.id
             GROUP BY c.id, c.title
             ORDER BY c.updated_at DESC, c.title ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(CabinetCollectionSummary {
                id: row.get(0)?,
                title: row.get(1)?,
                item_count: row.get(2)?,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn smart_folders(&self) -> CabinetResult<Vec<SmartFolderSummary>> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, title, condition_sql, display_condition FROM smart_folders ORDER BY title ASC")?;
        let rows = stmt.query_map([], |row| {
            let id: String = row.get(0)?;
            let title: String = row.get(1)?;
            let condition_sql: String = row.get(2)?;
            let display_condition: String = row.get(3)?;
            Ok((id, title, condition_sql, display_condition))
        })?;
        let mut result = Vec::new();
        for row in rows {
            let (id, title, condition_sql, display_condition) = row?;
            let sql = format!("SELECT COUNT(*) FROM cabinet_items WHERE {condition_sql}");
            let item_count = self.scalar(&sql)?;
            result.push(SmartFolderSummary {
                id,
                title,
                condition: display_condition,
                item_count,
            });
        }
        Ok(result)
    }

    fn previews_for_item(&self, item_id: &str) -> CabinetResult<Vec<CabinetPreviewSummary>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, preview_type, title, summary_text, status, generated_at
             FROM cabinet_previews
             WHERE item_id = ?1
             ORDER BY generated_at DESC",
        )?;
        let rows = stmt.query_map(params![item_id], |row| {
            Ok(CabinetPreviewSummary {
                id: row.get(0)?,
                preview_type: row.get(1)?,
                title: row.get(2)?,
                summary_text: row.get(3)?,
                status: row.get(4)?,
                generated_at: row.get(5)?,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn references_for_item(&self, item_id: &str) -> CabinetResult<Vec<CabinetReferenceSummary>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, reference_type, source_app, title, uri, note
             FROM cabinet_references
             WHERE item_id = ?1
             ORDER BY created_at DESC",
        )?;
        let rows = stmt.query_map(params![item_id], |row| {
            Ok(CabinetReferenceSummary {
                id: row.get(0)?,
                reference_type: row.get(1)?,
                source_app: row.get(2)?,
                title: row.get(3)?,
                uri: row.get(4)?,
                note: row.get(5)?,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn versions_for_document(
        &self,
        document_id: &str,
    ) -> CabinetResult<Vec<CabinetVersionSummary>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, version_number, display_name, note, is_current
             FROM cabinet_versions
             WHERE document_id = ?1
             ORDER BY version_number DESC",
        )?;
        let rows = stmt.query_map(params![document_id], |row| {
            Ok(CabinetVersionSummary {
                id: row.get(0)?,
                version_number: row.get(1)?,
                display_name: row.get(2)?,
                note: row.get(3)?,
                is_current: row.get::<_, i64>(4)? != 0,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn apply_version_to_item(
        &self,
        item_id: &str,
        version_id: &str,
        now: &str,
    ) -> CabinetResult<()> {
        let (document_id, file_id, display_name, mime_type, size, hash): (
            String,
            String,
            String,
            String,
            i64,
            String,
        ) = self.conn.query_row(
            "SELECT v.document_id, v.file_id, v.display_name, f.mime_type, f.size, f.hash
             FROM cabinet_versions v
             JOIN cabinet_files f ON f.id = v.file_id
             WHERE v.id = ?1",
            params![version_id],
            |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                    row.get(5)?,
                ))
            },
        )?;
        self.conn.execute(
            "UPDATE cabinet_versions SET is_current = CASE WHEN id = ?1 THEN 1 ELSE 0 END WHERE document_id = ?2",
            params![version_id, document_id],
        )?;
        self.conn.execute(
            "UPDATE cabinet_documents SET current_version_id = ?1, updated_at = ?2 WHERE id = ?3",
            params![version_id, now, document_id],
        )?;
        self.conn.execute(
            "UPDATE cabinet_items
             SET file_id = ?1, title = ?2, display_name = ?2, mime_type = ?3, size = ?4, hash = ?5, updated_at = ?6
             WHERE id = ?7",
            params![file_id, display_name, mime_type, size, hash, now, item_id],
        )?;
        self.rebuild_fts_for_item(item_id)?;
        Ok(())
    }

    fn storage_provider_accounts(&self) -> CabinetResult<Vec<StorageProviderAccountSummary>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, provider_type, display_name, account_name, auth_type, connection_status, last_connected_at
             FROM storage_provider_accounts
             ORDER BY
                CASE provider_type
                    WHEN 'local' THEN 0
                    WHEN 'usb' THEN 1
                    WHEN 'sdcard' THEN 2
                    ELSE 3
                END,
                display_name ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(StorageProviderAccountSummary {
                id: row.get(0)?,
                provider_type: row.get(1)?,
                display_name: row.get(2)?,
                account_name: row.get(3)?,
                auth_type: row.get(4)?,
                connection_status: row.get(5)?,
                last_connected_at: row.get(6)?,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn tags(&self) -> CabinetResult<Vec<TagSummary>> {
        let mut stmt = self
            .conn
            .prepare("SELECT id, name, color FROM cabinet_tags ORDER BY name ASC")?;
        let rows = stmt.query_map([], |row| {
            Ok(TagSummary {
                id: row.get(0)?,
                name: row.get(1)?,
                color: row.get(2)?,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn tags_for_item(&self, item_id: &str) -> CabinetResult<Vec<TagSummary>> {
        let mut stmt = self.conn.prepare(
            "SELECT t.id, t.name, t.color
             FROM cabinet_tags t
             JOIN cabinet_item_tags it ON it.tag_id = t.id
             WHERE it.item_id = ?1
             ORDER BY t.name ASC",
        )?;
        let rows = stmt.query_map(params![item_id], |row| {
            Ok(TagSummary {
                id: row.get(0)?,
                name: row.get(1)?,
                color: row.get(2)?,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn collections_for_item(&self, item_id: &str) -> CabinetResult<Vec<CabinetCollectionSummary>> {
        let mut stmt = self.conn.prepare(
            "SELECT c.id, c.title, COUNT(ci2.item_id)
             FROM cabinet_collections c
             JOIN cabinet_collection_items ci ON ci.collection_id = c.id
             LEFT JOIN cabinet_collection_items ci2 ON ci2.collection_id = c.id
             WHERE ci.item_id = ?1
             GROUP BY c.id, c.title
             ORDER BY c.title ASC",
        )?;
        let rows = stmt.query_map(params![item_id], |row| {
            Ok(CabinetCollectionSummary {
                id: row.get(0)?,
                title: row.get(1)?,
                item_count: row.get(2)?,
            })
        })?;
        let mut result = Vec::new();
        for row in rows {
            result.push(row?);
        }
        Ok(result)
    }

    fn backup_summary(&self) -> CabinetResult<BackupSummary> {
        Ok(BackupSummary {
            item_count: self.scalar("SELECT COUNT(*) FROM cabinet_items")?,
            collection_count: self.scalar("SELECT COUNT(*) FROM cabinet_collections")?,
            tag_count: self.scalar("SELECT COUNT(*) FROM cabinet_tags")?,
            preview_count: self.scalar("SELECT COUNT(*) FROM cabinet_previews")?,
            exported_at: now_string(),
        })
    }

    fn security_summary(&self) -> CabinetResult<SecuritySummary> {
        let pin_count =
            self.scalar("SELECT COUNT(*) FROM security_settings WHERE key = 'pin_hash'")?;
        Ok(SecuritySummary {
            pin_enabled: pin_count > 0,
            protected_item_count: self
                .scalar("SELECT COUNT(*) FROM cabinet_item_security WHERE is_protected = 1")?,
            protected_memo_count: self
                .scalar("SELECT COUNT(*) FROM cabinet_memos WHERE is_protected = 1")?,
        })
    }

    fn backup_tables_json(&self) -> CabinetResult<Map<String, Value>> {
        let mut tables = Map::new();
        for (table, columns) in BACKUP_TABLES {
            let sql = format!("SELECT {} FROM {table}", columns.join(", "));
            let mut stmt = self.conn.prepare(&sql)?;
            let rows = stmt.query_map([], |row| {
                let mut object = Map::new();
                for (index, column) in columns.iter().enumerate() {
                    object.insert(
                        (*column).to_owned(),
                        json_value_from_sql(row.get_ref(index)?),
                    );
                }
                Ok(Value::Object(object))
            })?;
            let mut values = Vec::new();
            for row in rows {
                values.push(row?);
            }
            tables.insert((*table).to_owned(), Value::Array(values));
        }
        Ok(tables)
    }

    fn restore_backup_tables(&self, tables: &Map<String, Value>) -> CabinetResult<()> {
        self.conn.execute("DELETE FROM cabinet_fts", [])?;
        for (table, _) in BACKUP_TABLES.iter().rev() {
            self.conn.execute(&format!("DELETE FROM {table}"), [])?;
        }

        for (table, columns) in BACKUP_TABLES {
            let rows = tables
                .get(*table)
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            if rows.is_empty() {
                continue;
            }
            let placeholders = std::iter::repeat_n("?", columns.len())
                .collect::<Vec<_>>()
                .join(", ");
            let sql = format!(
                "INSERT INTO {table} ({}) VALUES ({placeholders})",
                columns.join(", ")
            );
            let mut stmt = self.conn.prepare(&sql)?;
            for row in rows {
                let object = row.as_object().ok_or_else(|| {
                    CabinetError::Message(format!(
                        "Backup table {table} contains a non-object row."
                    ))
                })?;
                let values = columns
                    .iter()
                    .map(|column| sql_value_from_json(object.get(*column).unwrap_or(&Value::Null)))
                    .collect::<Vec<_>>();
                stmt.execute(params_from_iter(values))?;
            }
        }
        Ok(())
    }

    fn duplicate_groups(&self) -> CabinetResult<Vec<DuplicateGroup>> {
        let mut stmt = self.conn.prepare(
            "SELECT hash, size
             FROM cabinet_items
             WHERE hash <> '' AND is_archived = 0
             GROUP BY hash, size
             HAVING COUNT(*) > 1
             ORDER BY size DESC, hash ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?))
        })?;
        let mut groups = Vec::new();
        for row in rows {
            let (hash, size) = row?;
            let items = self.query_items(
                "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                 FROM cabinet_items
                 WHERE hash = ?1 AND size = ?2 AND is_archived = 0
                 ORDER BY updated_at DESC, title ASC",
                params![hash, size],
            )?;
            groups.push(DuplicateGroup { hash, size, items });
        }
        Ok(groups)
    }

    fn item_by_id(&self, id: &str) -> CabinetResult<Option<CabinetItemSummary>> {
        self.conn
            .query_row(
                "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                 FROM cabinet_items WHERE id = ?1",
                params![id],
                read_item_summary,
            )
            .optional()
            .map_err(CabinetError::from)
    }

    fn ensure_item_exists(&self, item_id: &str) -> CabinetResult<()> {
        let exists: i64 = self.conn.query_row(
            "SELECT COUNT(*) FROM cabinet_items WHERE id = ?1",
            params![item_id],
            |row| row.get(0),
        )?;
        if exists == 0 {
            return Err(CabinetError::Message(format!("Item not found: {item_id}")));
        }
        Ok(())
    }

    fn file_paths_for_item(&self, item_id: &str) -> CabinetResult<Vec<String>> {
        let mut paths = Vec::new();
        if let Some(path) = self
            .conn
            .query_row(
                "SELECT NULLIF(path, '') FROM cabinet_items WHERE id = ?1",
                params![item_id],
                |row| row.get::<_, Option<String>>(0),
            )
            .optional()?
            .flatten()
        {
            paths.push(path);
        }
        let mut stmt = self.conn.prepare(
            "SELECT f.path
             FROM cabinet_items i
             JOIN cabinet_files f ON f.id = i.file_id
             WHERE i.id = ?1 AND f.path <> ''",
        )?;
        let rows = stmt.query_map(params![item_id], |row| row.get::<_, String>(0))?;
        for row in rows {
            let path = row?;
            if !paths.contains(&path) {
                paths.push(path);
            }
        }
        Ok(paths)
    }

    fn query_items<P>(&self, sql: &str, params: P) -> CabinetResult<Vec<CabinetItemSummary>>
    where
        P: rusqlite::Params,
    {
        let mut stmt = self.conn.prepare(sql)?;
        let rows = stmt.query_map(params, read_item_summary)?;
        let mut items = Vec::new();
        for row in rows {
            items.push(row?);
        }
        Ok(items)
    }

    fn log_event(
        &self,
        event_type: &str,
        item_id: Option<&str>,
        payload: Value,
    ) -> CabinetResult<()> {
        self.conn.execute(
            "INSERT INTO cabinet_events(id, event_type, item_id, payload_json, created_at)
             VALUES (?1, ?2, ?3, ?4, ?5)",
            params![
                new_id(),
                event_type,
                item_id,
                payload.to_string(),
                now_string()
            ],
        )?;
        Ok(())
    }

    fn scalar(&self, sql: &str) -> CabinetResult<i64> {
        self.conn
            .query_row(sql, [], |row| row.get(0))
            .map_err(CabinetError::from)
    }

    fn rebuild_fts_for_item(&self, item_id: &str) -> CabinetResult<()> {
        self.conn.execute(
            "DELETE FROM cabinet_fts WHERE item_id = ?1",
            params![item_id],
        )?;
        self.conn.execute(
            "INSERT INTO cabinet_fts(item_id, title, display_name, mime_type, tags, collections, note, preview_text, references_text)
             SELECT i.id,
                    i.title,
                    i.display_name,
                    i.mime_type,
                    COALESCE((SELECT group_concat(t.name, ' ') FROM cabinet_item_tags it JOIN cabinet_tags t ON t.id = it.tag_id WHERE it.item_id = i.id), ''),
                    COALESCE((SELECT group_concat(c.title, ' ') FROM cabinet_collection_items ci JOIN cabinet_collections c ON c.id = ci.collection_id WHERE ci.item_id = i.id), ''),
                    i.note,
                    COALESCE((SELECT group_concat(p.summary_text || ' ' || p.extracted_text, ' ') FROM cabinet_previews p WHERE p.item_id = i.id), ''),
                    COALESCE((SELECT group_concat(r.title || ' ' || r.note, ' ') FROM cabinet_references r WHERE r.item_id = i.id), '')
             FROM cabinet_items i
             WHERE i.id = ?1",
            params![item_id],
        )?;
        Ok(())
    }

    fn rebuild_fts(&self) -> CabinetResult<()> {
        self.conn.execute("DELETE FROM cabinet_fts", [])?;
        let mut stmt = self.conn.prepare("SELECT id FROM cabinet_items")?;
        let rows = stmt.query_map([], |row| row.get::<_, String>(0))?;
        let mut ids = Vec::new();
        for row in rows {
            ids.push(row?);
        }
        for item_id in ids {
            self.rebuild_fts_for_item(&item_id)?;
        }
        Ok(())
    }
}

fn read_item_summary(row: &rusqlite::Row<'_>) -> rusqlite::Result<CabinetItemSummary> {
    Ok(CabinetItemSummary {
        id: row.get(0)?,
        title: row.get(1)?,
        display_name: row.get(2)?,
        mime_type: row.get(3)?,
        source_kind: row.get(4)?,
        size: row.get(5)?,
        is_favorite: row.get::<_, i64>(6)? != 0,
        is_unsorted: row.get::<_, i64>(7)? != 0,
        summary_text: row.get(8)?,
        updated_at: row.get(9)?,
    })
}

fn json_value_from_sql(value: ValueRef<'_>) -> Value {
    match value {
        ValueRef::Null => Value::Null,
        ValueRef::Integer(value) => Value::from(value),
        ValueRef::Real(value) => Value::from(value),
        ValueRef::Text(value) => Value::from(String::from_utf8_lossy(value).into_owned()),
        ValueRef::Blob(value) => {
            Value::Array(value.iter().map(|byte| Value::from(*byte)).collect())
        }
    }
}

fn sql_value_from_json(value: &Value) -> SqlValue {
    match value {
        Value::Null => SqlValue::Null,
        Value::Bool(value) => SqlValue::Integer(if *value { 1 } else { 0 }),
        Value::Number(value) => value
            .as_i64()
            .map(SqlValue::Integer)
            .or_else(|| value.as_f64().map(SqlValue::Real))
            .unwrap_or(SqlValue::Null),
        Value::String(value) => SqlValue::Text(value.clone()),
        Value::Array(_) | Value::Object(_) => SqlValue::Text(value.to_string()),
    }
}

fn now_string() -> String {
    OffsetDateTime::now_utc()
        .format(&time::format_description::well_known::Rfc3339)
        .unwrap_or_else(|_| "1970-01-01T00:00:00Z".to_owned())
}

fn new_id() -> String {
    Uuid::new_v4().to_string()
}

fn hash_text(value: &str) -> String {
    let mut hasher = Sha256::new();
    hasher.update(value.as_bytes());
    format!("{:x}", hasher.finalize())
}

fn hash_file(path: &str) -> std::io::Result<String> {
    let mut file = File::open(path)?;
    let mut hasher = Sha256::new();
    let mut buffer = [0_u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer)?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Ok(format!("{:x}", hasher.finalize()))
}

fn preview_type_for_mime(mime_type: &str) -> &'static str {
    match mime_type {
        "application/pdf" => "pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" => "office",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" => "office",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" => "office",
        "text/markdown" => "markdown",
        "text/uri-list" => "url",
        "application/zip" => "archive",
        _ if mime_type.starts_with("image/") => "image",
        _ if mime_type.starts_with("video/") => "video",
        _ if mime_type.starts_with("audio/") => "audio",
        _ => "text",
    }
}

fn preview_for_file(path: &str, mime_type: &str, display_name: &str) -> GeneratedPreview {
    let lower_name = display_name.to_ascii_lowercase();
    if mime_type.starts_with("text/")
        || lower_name.ends_with(".md")
        || lower_name.ends_with(".markdown")
        || lower_name.ends_with(".txt")
    {
        let summary = std::fs::read_to_string(path)
            .map(|text| text_preview(&text))
            .unwrap_or_default();
        return generated_preview(summary, None, None);
    }
    if mime_type == "application/zip" || lower_name.ends_with(".zip") {
        let summary = zip_preview(path).unwrap_or_else(|| {
            format!("{display_name}: ZIP archive. 解凍操作で中のファイルをCabinetへ登録できます。")
        });
        return generated_preview(summary, None, None);
    }
    if lower_name.ends_with(".docx") {
        let summary = office_preview(path, OfficeKind::Word).unwrap_or_else(|| {
            format!("{display_name}: Word document. Office XMLプレビュー解析の対象です。")
        });
        return generated_preview(
            format!("{display_name}: Word document\n{summary}"),
            None,
            None,
        );
    }
    if lower_name.ends_with(".xlsx") {
        let summary = office_preview(path, OfficeKind::Excel).unwrap_or_else(|| {
            format!("{display_name}: Excel workbook. Office XMLプレビュー解析の対象です。")
        });
        return generated_preview(
            format!("{display_name}: Excel workbook\n{summary}"),
            None,
            None,
        );
    }
    if lower_name.ends_with(".pptx") {
        let summary = office_preview(path, OfficeKind::PowerPoint).unwrap_or_else(|| {
            format!("{display_name}: PowerPoint presentation. Office XMLプレビュー解析の対象です。")
        });
        return generated_preview(
            format!("{display_name}: PowerPoint presentation\n{summary}"),
            None,
            None,
        );
    }
    if mime_type == "application/pdf" || lower_name.ends_with(".pdf") {
        return generated_preview(
            format!("{display_name}: PDF document. 本文抽出とページプレビュー生成の対象です。"),
            None,
            None,
        );
    }
    if mime_type.starts_with("image/") {
        let dimensions = image_dimensions(path);
        let summary = match dimensions {
            Some((width, height)) => {
                format!(
                    "{display_name}: image file, {width} x {height}px. OCRとサムネイル生成の対象です。"
                )
            }
            None => format!("{display_name}: image file. OCRとサムネイル生成の対象です。"),
        };
        return generated_preview(
            summary,
            dimensions.map(|value| value.0),
            dimensions.map(|value| value.1),
        );
    }
    if mime_type.starts_with("video/") {
        return generated_preview(
            format!("{display_name}: video file. サムネイル、長さ、解像度抽出の対象です。"),
            None,
            None,
        );
    }
    if mime_type.starts_with("audio/") {
        return generated_preview(
            format!("{display_name}: audio file. 長さとタグ情報抽出の対象です。"),
            None,
            None,
        );
    }
    GeneratedPreview {
        summary_text: String::new(),
        width: None,
        height: None,
        status: "unsupported",
    }
}

fn generated_preview(
    summary_text: String,
    width: Option<i64>,
    height: Option<i64>,
) -> GeneratedPreview {
    let status = if summary_text.is_empty() {
        "unsupported"
    } else {
        "ready"
    };
    GeneratedPreview {
        summary_text,
        width,
        height,
        status,
    }
}

fn text_preview(text: &str) -> String {
    let mut lines = Vec::new();
    for line in text.lines() {
        let normalized = line.trim();
        if normalized.is_empty() {
            continue;
        }
        lines.push(normalized.to_owned());
        if lines.len() >= 12 {
            break;
        }
    }
    let joined = lines.join("\n");
    joined.chars().take(2000).collect()
}

enum OfficeKind {
    Word,
    Excel,
    PowerPoint,
}

fn zip_preview(path: &str) -> Option<String> {
    let file = File::open(path).ok()?;
    let mut archive = ZipArchive::new(file).ok()?;
    let mut names = Vec::new();
    let mut total_uncompressed = 0_u64;
    for index in 0..archive.len().min(30) {
        let entry = archive.by_index(index).ok()?;
        total_uncompressed = total_uncompressed.saturating_add(entry.size());
        names.push(entry.name().to_owned());
    }
    if names.is_empty() {
        return None;
    }
    Some(format!(
        "ZIP archive: {} entries, {} bytes unpacked\n{}",
        archive.len(),
        total_uncompressed,
        names.join("\n")
    ))
}

fn office_preview(path: &str, kind: OfficeKind) -> Option<String> {
    let file = File::open(path).ok()?;
    let mut archive = ZipArchive::new(file).ok()?;
    let mut text = String::new();
    match kind {
        OfficeKind::Word => {
            read_zip_text(&mut archive, "word/document.xml", &mut text);
        }
        OfficeKind::Excel => {
            read_zip_text(&mut archive, "xl/sharedStrings.xml", &mut text);
            if text.is_empty() {
                read_first_matching_zip_text(&mut archive, "xl/worksheets/sheet", &mut text);
            }
        }
        OfficeKind::PowerPoint => {
            read_first_matching_zip_text(&mut archive, "ppt/slides/slide", &mut text);
        }
    }
    let plain = xml_text_preview(&text);
    if plain.is_empty() { None } else { Some(plain) }
}

fn read_zip_text<R: std::io::Read + std::io::Seek>(
    archive: &mut ZipArchive<R>,
    name: &str,
    output: &mut String,
) {
    if let Ok(mut entry) = archive.by_name(name) {
        let mut buffer = String::new();
        if entry.read_to_string(&mut buffer).is_ok() {
            output.push_str(&buffer);
        }
    }
}

fn read_first_matching_zip_text<R: std::io::Read + std::io::Seek>(
    archive: &mut ZipArchive<R>,
    prefix: &str,
    output: &mut String,
) {
    let index = (0..archive.len()).find(|index| {
        archive
            .by_index(*index)
            .map(|entry| entry.name().starts_with(prefix) && entry.name().ends_with(".xml"))
            .unwrap_or(false)
    });
    if let Some(index) = index {
        if let Ok(mut entry) = archive.by_index(index) {
            let mut buffer = String::new();
            if entry.read_to_string(&mut buffer).is_ok() {
                output.push_str(&buffer);
            }
        }
    }
}

fn xml_text_preview(xml: &str) -> String {
    let mut result = String::new();
    let mut in_tag = false;
    let mut last_was_space = true;
    for ch in xml.chars() {
        match ch {
            '<' => in_tag = true,
            '>' => {
                in_tag = false;
                if !last_was_space {
                    result.push(' ');
                    last_was_space = true;
                }
            }
            _ if in_tag => {}
            _ if ch.is_whitespace() => {
                if !last_was_space {
                    result.push(' ');
                    last_was_space = true;
                }
            }
            _ => {
                result.push(ch);
                last_was_space = false;
            }
        }
        if result.len() >= 4000 {
            break;
        }
    }
    decode_xml_entities(&result).chars().take(2000).collect()
}

fn decode_xml_entities(value: &str) -> String {
    value
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .trim()
        .to_owned()
}

fn image_dimensions(path: &str) -> Option<(i64, i64)> {
    let mut buffer = [0_u8; 32];
    let mut file = File::open(path).ok()?;
    let read = file.read(&mut buffer).ok()?;
    let bytes = &buffer[..read];
    if bytes.len() >= 24 && &bytes[0..8] == b"\x89PNG\r\n\x1a\n" {
        let width = u32::from_be_bytes(bytes[16..20].try_into().ok()?);
        let height = u32::from_be_bytes(bytes[20..24].try_into().ok()?);
        return Some((i64::from(width), i64::from(height)));
    }
    if bytes.len() >= 10 && &bytes[0..6] == b"GIF87a"
        || bytes.len() >= 10 && &bytes[0..6] == b"GIF89a"
    {
        let width = u16::from_le_bytes(bytes[6..8].try_into().ok()?);
        let height = u16::from_le_bytes(bytes[8..10].try_into().ok()?);
        return Some((i64::from(width), i64::from(height)));
    }
    jpeg_dimensions(path)
}

fn jpeg_dimensions(path: &str) -> Option<(i64, i64)> {
    let bytes = std::fs::read(path).ok()?;
    if bytes.len() < 4 || bytes[0] != 0xff || bytes[1] != 0xd8 {
        return None;
    }
    let mut index = 2;
    while index + 9 < bytes.len() {
        if bytes[index] != 0xff {
            index += 1;
            continue;
        }
        let marker = bytes[index + 1];
        index += 2;
        if marker == 0xd9 || marker == 0xda {
            break;
        }
        if index + 2 > bytes.len() {
            break;
        }
        let length = usize::from(u16::from_be_bytes(bytes[index..index + 2].try_into().ok()?));
        if length < 2 || index + length > bytes.len() {
            break;
        }
        if matches!(
            marker,
            0xc0 | 0xc1
                | 0xc2
                | 0xc3
                | 0xc5
                | 0xc6
                | 0xc7
                | 0xc9
                | 0xca
                | 0xcb
                | 0xcd
                | 0xce
                | 0xcf
        ) {
            let height = u16::from_be_bytes(bytes[index + 3..index + 5].try_into().ok()?);
            let width = u16::from_be_bytes(bytes[index + 5..index + 7].try_into().ok()?);
            return Some((i64::from(width), i64::from(height)));
        }
        index += length;
    }
    None
}

fn duplicate_path(source: &Path) -> PathBuf {
    let parent = source.parent().unwrap_or_else(|| Path::new(""));
    let stem = source
        .file_stem()
        .and_then(|value| value.to_str())
        .unwrap_or("copy");
    let extension = source.extension().and_then(|value| value.to_str());
    for index in 1..1000 {
        let file_name = match extension {
            Some(extension) if !extension.is_empty() => format!("{stem}-copy-{index}.{extension}"),
            _ => format!("{stem}-copy-{index}"),
        };
        let candidate = parent.join(file_name);
        if !candidate.exists() {
            return candidate;
        }
    }
    parent.join(format!("{stem}-copy"))
}

fn escape_fts_token(value: &str) -> String {
    value
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_' || *c as u32 >= 0x80)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn test_db_path(name: &str) -> std::path::PathBuf {
        let mut path = std::env::temp_dir();
        path.push(format!("cabinet-strasse-{name}-{}.db", Uuid::new_v4()));
        path
    }

    #[test]
    fn seeds_dashboard_and_settings() {
        let path = test_db_path("dashboard");
        let core = CabinetCore::open(&path).expect("open database");
        let dashboard = core.dashboard().expect("dashboard");
        assert!(dashboard.library_count >= 4);
        assert!(dashboard.collection_count >= 1);
        let settings = core.settings_json().expect("settings json");
        assert!(settings.contains("Google Drive"));
        let _ = fs::remove_file(path);
    }

    #[test]
    fn registers_url_and_searches_fts() {
        let path = test_db_path("url");
        let core = CabinetCore::open(&path).expect("open database");
        let item_json = core
            .register_url_json(
                "https://example.com/cabinet",
                "Example Cabinet",
                "Searchable memo",
            )
            .expect("register url");
        assert!(item_json.contains("Example Cabinet"));
        let search = core.search("Searchable").expect("search");
        assert!(search.iter().any(|item| item.title == "Example Cabinet"));
        let _ = fs::remove_file(path);
    }

    #[test]
    fn updates_flags_and_exports_backup() {
        let path = test_db_path("flags");
        let core = CabinetCore::open(&path).expect("open database");
        let first_item = core.dashboard().expect("dashboard").recent_items[0]
            .id
            .clone();
        let detail_json = core
            .update_item_flags_json(&first_item, true, false)
            .expect("update flags");
        assert!(detail_json.contains("\"is_favorite\":true"));
        assert!(detail_json.contains("\"is_unsorted\":false"));
        let backup = core.backup_export_json().expect("backup");
        assert!(backup.contains("Cabinet-STRASSE"));
        let _ = fs::remove_file(path);
    }
}
