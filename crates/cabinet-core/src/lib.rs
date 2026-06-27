use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use sha2::{Digest, Sha256};
use std::fs::File;
use std::io::Read;
use std::path::Path;
use time::OffsetDateTime;
use uuid::Uuid;

const SCHEMA_VERSION: i64 = 1;

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
                     WHERE source_kind IN ('local', 'mail')
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
                     WHERE is_unsorted = 1
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
            let text = preview_text_for_file(&path, &mime_type, &display_name);
            let status = if text.is_empty() {
                "unsupported"
            } else {
                "ready"
            };
            self.conn.execute(
                "UPDATE cabinet_previews
                 SET summary_text = ?1, extracted_text = ?1, generated_at = ?2, status = ?3
                 WHERE id = ?4",
                params![text, now_string(), status, preview_id],
            )?;
            self.rebuild_fts_for_item(&item_id)?;
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
        };
        Ok(serde_json::to_string(&snapshot)?)
    }

    pub fn backup_export_json(&self) -> CabinetResult<String> {
        let value = serde_json::json!({
            "app": "Cabinet-STRASSE",
            "schema_version": SCHEMA_VERSION,
            "exported_at": now_string(),
            "summary": self.backup_summary()?,
            "items": self.search("")?,
            "collections": self.collections()?,
            "smart_folders": self.smart_folders()?,
            "providers": self.storage_provider_accounts()?,
        });
        Ok(serde_json::to_string_pretty(&value)?)
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
            explorer_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE source_kind IN ('local', 'mail')")?,
            library_count: self.scalar("SELECT COUNT(*) FROM cabinet_items")?,
            collection_count: self.scalar("SELECT COUNT(*) FROM cabinet_collections")?,
            inbox_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE is_unsorted = 1")?,
            favorite_count: self.scalar("SELECT COUNT(*) FROM cabinet_items WHERE is_favorite = 1")?,
            recent_items: self.query_items(
                "SELECT id, title, display_name, mime_type, source_kind, size, is_favorite, is_unsorted, note, updated_at
                 FROM cabinet_items
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
             WHERE cabinet_fts MATCH ?1
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

    fn backup_summary(&self) -> CabinetResult<BackupSummary> {
        Ok(BackupSummary {
            item_count: self.scalar("SELECT COUNT(*) FROM cabinet_items")?,
            collection_count: self.scalar("SELECT COUNT(*) FROM cabinet_collections")?,
            tag_count: self.scalar("SELECT COUNT(*) FROM cabinet_tags")?,
            preview_count: self.scalar("SELECT COUNT(*) FROM cabinet_previews")?,
            exported_at: now_string(),
        })
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
        "text/markdown" => "markdown",
        "text/uri-list" => "url",
        "application/zip" => "archive",
        _ if mime_type.starts_with("image/") => "image",
        _ if mime_type.starts_with("video/") => "video",
        _ if mime_type.starts_with("audio/") => "audio",
        _ => "text",
    }
}

fn preview_text_for_file(path: &str, mime_type: &str, display_name: &str) -> String {
    if mime_type.starts_with("text/")
        || display_name.ends_with(".md")
        || display_name.ends_with(".txt")
    {
        return std::fs::read_to_string(path)
            .map(|text| text.chars().take(2000).collect())
            .unwrap_or_default();
    }
    if mime_type == "application/zip" {
        return format!("{display_name} is an archive stored in Cabinet Inbox.");
    }
    if mime_type == "application/pdf" {
        return format!(
            "{display_name} is queued as a PDF document. Full PDF text extraction is handled by the preview pipeline."
        );
    }
    if mime_type.starts_with("image/") {
        return format!("{display_name} is an image file queued for thumbnail and OCR processing.");
    }
    if mime_type.starts_with("video/") {
        return format!(
            "{display_name} is a video file queued for duration and thumbnail extraction."
        );
    }
    if mime_type.starts_with("audio/") {
        return format!("{display_name} is an audio file queued for duration and tag extraction.");
    }
    String::new()
}

fn escape_fts_token(value: &str) -> String {
    value
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_' || *c as u32 >= 0x80)
        .collect()
}
