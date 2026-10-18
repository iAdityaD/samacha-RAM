import json
import os
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from cryptography.fernet import Fernet


def now():
    return datetime.now(timezone.utc).isoformat()


DEFAULT_PROFILE = {'topics': {'LLMs': 'Core', 'NLP': 'Near', 'Hardware': 'Adjacent', 'AI audio': 'Adjacent', 'AI video': 'Discovery', 'Software': 'Near'}, 'budget': 15, 'tracking': True, 'local_only': False, 'cloud_fallback': False, 'theme': 'light', 'digest_hour': '08:00', 'timezone': 'Asia/Kolkata'}
RECORDS = ('articles','sources','providers','missions','queries','outputs','jobs','documents','claims','reviews','relations','watches','alerts','feedback','annotations','vectors','operations','comparisons','versions','stories')


class Store:
    def __init__(self, root):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.lock = threading.RLock()
        path = self.root / 'vault.key'
        if not path.exists():
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, 'wb') as f:
                f.write(Fernet.generate_key())
        self.cipher = Fernet(path.read_bytes())
        self.db = sqlite3.connect(self.root / 'reader.sqlite3', check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript('''
        PRAGMA journal_mode=WAL;
        CREATE TABLE IF NOT EXISTS settings (id TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS articles (id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS sources (id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS providers (id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS saved (id TEXT PRIMARY KEY, created TEXT NOT NULL, note TEXT NOT NULL DEFAULT '');
        CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY, article TEXT NOT NULL, kind TEXT NOT NULL, created TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS missions (id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS queries (id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS outputs (id TEXT PRIMARY KEY, body TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS jobs (id TEXT PRIMARY KEY, body TEXT NOT NULL);
        ''')
        self.db.commit()
        for table in RECORDS:
            self.db.execute(f'CREATE TABLE IF NOT EXISTS {table}(id TEXT PRIMARY KEY, body TEXT NOT NULL)')
        if 'version' not in [r[1] for r in self.db.execute('PRAGMA table_info(history)')]:
            self.db.execute('ALTER TABLE history ADD COLUMN version TEXT')
        self.db.execute('PRAGMA user_version=3')
        self.db.commit()

    def put(self, table, id, body):
        if table not in RECORDS:
            raise ValueError('Invalid record type')
        with self.lock, self.db:
            if table=='articles':
                old=self.db.execute('SELECT body FROM articles WHERE id=?',(id,)).fetchone()
                if old:
                    previous=json.loads(old['body'])
                    if previous.get('version')!=body.get('version'):
                        self.db.execute('INSERT OR IGNORE INTO versions VALUES (?,?)',(id+':'+previous['version'],json.dumps(previous)))
                        for derived in ('outputs','vectors'):
                            self.db.execute(f"DELETE FROM {derived} WHERE json_extract(body,'$.article_id')=?",(id,))
                        for table_name in ('reviews','relations','annotations','comparisons'):
                            for derived_record in self.items(table_name):
                                if id in json.dumps(derived_record):
                                    derived_record['stale']=True
                                    self.db.execute(f'UPDATE {table_name} SET body=? WHERE id=?',(json.dumps(derived_record),derived_record['id']))
            self.db.execute(f'INSERT OR REPLACE INTO {table}(id,body) VALUES (?,?)', (id, json.dumps(body)))

    def items(self, table):
        if table not in RECORDS:
            raise ValueError('Invalid record type')
        with self.lock:
            return [json.loads(x['body']) for x in self.db.execute(f'SELECT body FROM {table}')]

    def delete(self, table, id):
        if table not in RECORDS+('saved',):
            raise ValueError('Invalid record type')
        with self.lock, self.db:
            self.db.execute(f'DELETE FROM {table} WHERE id=?', (id,))

    def setting(self, key, default=None):
        with self.lock:
            row = self.db.execute('SELECT value FROM settings WHERE id=?', (key,)).fetchone()
            return json.loads(row['value']) if row else default

    def set_setting(self, key, value):
        with self.lock, self.db:
            self.db.execute('INSERT OR REPLACE INTO settings VALUES (?,?)', (key, json.dumps(value)))

    def profile(self):
        return {**DEFAULT_PROFILE, **self.setting('profile', {})}

    def save(self, id, active=True, note=None):
        with self.lock, self.db:
            if active:
                self.db.execute('INSERT OR IGNORE INTO saved(id,created) VALUES (?,?)', (id, now()))
                if note is not None:
                    self.db.execute('UPDATE saved SET note=? WHERE id=?', (note[:20000], id))
            else:
                self.db.execute('DELETE FROM saved WHERE id=?', (id,))

    def saved(self):
        with self.lock:
            return [dict(x) for x in self.db.execute('SELECT * FROM saved ORDER BY created DESC')]

    def event(self, id, kind):
        if kind not in ('metadata_open', 'summary_open', 'original_open', 'mark_read', 'document_open'):
            raise ValueError('Invalid reading event')
        if self.profile()['tracking']:
            with self.lock, self.db:
                row=self.db.execute('SELECT body FROM articles WHERE id=?',(id,)).fetchone()
                version=json.loads(row['body']).get('version') if row else None
                self.db.execute('INSERT INTO history(article,kind,created,version) VALUES (?,?,?,?)', (id, kind, now(),version))

    def history(self):
        with self.lock:
            return [dict(x) for x in self.db.execute('SELECT * FROM history ORDER BY id DESC LIMIT 1000')]

    def clear_history(self):
        with self.lock, self.db:
            self.db.execute('DELETE FROM history')
            self.db.execute('DELETE FROM outputs')
            self.db.execute('DELETE FROM feedback')
            self.db.execute("DELETE FROM jobs WHERE json_extract(body,'$.kind')='inference'")
            for operation in self.items('operations'):
                operation['result']={'redacted_after_history_clear':True}
                self.db.execute('UPDATE operations SET body=? WHERE id=?',(json.dumps(operation),operation['id']))
            self.db.execute("DELETE FROM settings WHERE id='behavior'")

    def encrypt(self, key):
        return self.cipher.encrypt(key.encode()).decode() if key else ''

    def decrypt(self, key):
        return self.cipher.decrypt(key.encode()).decode() if key else ''

    def close(self):
        self.db.close()
