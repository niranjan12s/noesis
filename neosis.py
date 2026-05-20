import os
import sys
import json
import sqlite3
import argparse
from datetime import datetime

# ANSI Escape Sequences for Premium Styling
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
BLUE = "\033[94m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"

DB_DIR = ".neosis"
DB_FILE = os.path.join(DB_DIR, "state.db")
CONFIG_FILE = os.path.join(DB_DIR, "config.json")
GITIGNORE_FILE = os.path.join(DB_DIR, ".gitignore")

# Setup UTF-8 encoding support on windows terminal if possible
try:
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
except Exception:
    pass

def format_timestamp(ts_value):
    if ts_value is None or ts_value == '' or ts_value == 0:
        return "N/A"
    try:
        val = int(ts_value)
        if val > 1e10:  # epoch millis (13 digits)
            return datetime.fromtimestamp(val / 1000).strftime('%Y-%m-%d %H:%M:%S')
        elif val > 1e8:  # epoch seconds (10 digits)
            return datetime.fromtimestamp(val).strftime('%Y-%m-%d %H:%M:%S')
    except (ValueError, TypeError, OSError):
        pass
    try:
        dt = datetime.fromisoformat(str(ts_value).replace('Z', '+00:00'))
        return dt.strftime('%Y-%m-%d %H:%M:%S')
    except (ValueError, TypeError):
        return str(ts_value)

def safe_print(text, is_error=False):
    target = sys.stderr if is_error else sys.stdout
    try:
        target.write(text + "\n")
        target.flush()
    except UnicodeEncodeError:
        # Fall back to safe ASCII symbols if terminal doesn't support unicode
        ascii_text = (text
                      .replace("✓", "[OK]")
                      .replace("✗", "[FAIL]")
                      .replace("⟳", "[RETRY]")
                      .replace("•", "*")
                      .replace("★", "*"))
        # Strip remaining non-ascii characters
        clean_text = ascii_text.encode('ascii', 'replace').decode('ascii')
        target.write(clean_text + "\n")
        target.flush()

def init_cmd():
    safe_print(f"{BOLD}{CYAN}Initializing Neosis Developer Environment...{RESET}")
    
    # 1. Create .neosis directory
    if not os.path.exists(DB_DIR):
        os.makedirs(DB_DIR)
        safe_print(f"  {GREEN}✓{RESET} Created {DB_DIR}/ directory")
    else:
        safe_print(f"  {BLUE}•{RESET} Directory {DB_DIR}/ already exists")

    # 2. Create config.json
    default_config = {
        "include": [
            "**/*.md",
            "docs/**/*.md",
            "README.md"
        ],
        "exclude": [
            "node_modules/**",
            ".git/**",
            "dist/**",
            "build/**",
            ".neosis/**"
        ]
    }
    if not os.path.exists(CONFIG_FILE):
        with open(CONFIG_FILE, "w") as f:
            json.dump(default_config, f, indent=2)
        safe_print(f"  {GREEN}✓{RESET} Generated default config at {CONFIG_FILE}")
    else:
        safe_print(f"  {BLUE}•{RESET} Config {CONFIG_FILE} already exists")

    # 3. Create .gitignore
    default_gitignore = "state.db\ncache/\nretries/\n"
    if not os.path.exists(GITIGNORE_FILE):
        with open(GITIGNORE_FILE, "w") as f:
            f.write(default_gitignore)
        safe_print(f"  {GREEN}✓{RESET} Created gitignore rule file at {GITIGNORE_FILE}")
    else:
        safe_print(f"  {BLUE}•{RESET} Gitignore file {GITIGNORE_FILE} already exists")

    # 4. Create subdirectories
    for subdir in ["cache", "retries"]:
        path = os.path.join(DB_DIR, subdir)
        if not os.path.exists(path):
            os.makedirs(path)
            safe_print(f"  {GREEN}✓{RESET} Created sub-directory {path}")

    # 5. Initialize SQLite DB schema
    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("""
            CREATE TABLE IF NOT EXISTS document_state (
                document_id VARCHAR(36) PRIMARY KEY,
                path TEXT UNIQUE,
                status VARCHAR(32),
                current_stage VARCHAR(64),
                retry_count INTEGER DEFAULT 0,
                last_error TEXT,
                next_retry_at TIMESTAMP,
                completed_chunks TEXT,
                total_chunks INTEGER DEFAULT 0,
                checksum VARCHAR(64),
                updated_at TIMESTAMP
            );
        """)
        conn.commit()
        conn.close()
        safe_print(f"  {GREEN}✓{RESET} SQLite state database schema created/verified")
    except Exception as e:
        safe_print(f"  {RED}✗{RESET} SQLite initialization failed: {e}", is_error=True)
        sys.exit(1)

    safe_print(f"\n{BOLD}{GREEN}★ Neosis successfully initialized!{RESET} You are ready to watch and index documents.")

def status_cmd():
    if not os.path.exists(DB_FILE):
        safe_print(f"{RED}Error: Neosis environment has not been initialized. Run 'neosis init' first.{RESET}", is_error=True)
        sys.exit(1)

    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("SELECT path, status, current_stage, retry_count, total_chunks, completed_chunks FROM document_state ORDER BY path ASC")
        rows = cursor.fetchall()
        conn.close()
    except Exception as e:
        safe_print(f"{RED}Error connecting to state database: {e}{RESET}", is_error=True)
        sys.exit(1)

    if not rows:
        safe_print(f"{YELLOW}No documents are currently tracked in Neosis.{RESET}")
        return

    # Print beautiful status list
    safe_print(f"\n{BOLD}{CYAN}{'File Path':<45} {'Pipeline State':<25} {'Progress':<10}{RESET}")
    safe_print(f"{CYAN}{'-' * 83}{RESET}")

    for path, status, stage, retry_count, total_chunks, completed_chunks in rows:
        # Determine status symbol & color
        if status == "QUERYABLE":
            symbol = f"{GREEN}✓{RESET}"
            status_str = f"{GREEN}QUERYABLE{RESET}"
        elif status == "RETRYING":
            symbol = f"{YELLOW}⟳{RESET}"
            status_str = f"{YELLOW}RETRYING (Attempt {retry_count}){RESET}"
        elif status == "FAILED_FATAL":
            symbol = f"{RED}✗{RESET}"
            status_str = f"{RED}FAILED_FATAL{RESET}"
        else:
            symbol = f"{BLUE}•{RESET}"
            status_str = f"{CYAN}{status} ({stage}){RESET}"

        # Progress fraction
        completed_count = 0
        if completed_chunks:
            completed_count = len(completed_chunks.split(","))
        
        progress = f"{completed_count}/{total_chunks}" if total_chunks > 0 else "0/0"
        
        safe_print(f"{symbol} {path:<43} {status_str:<25} {progress:<10}")
    safe_print("")

def logs_cmd(file_path):
    if not os.path.exists(DB_FILE):
        safe_print(f"{RED}Error: Neosis environment has not been initialized. Run 'neosis init' first.{RESET}", is_error=True)
        sys.exit(1)

    # Normalize relative path to match DB entries
    root_dir = os.path.abspath(".")
    target_abs = os.path.abspath(file_path)
    try:
        rel_path = os.path.relpath(target_abs, root_dir).replace('\\', '/')
    except Exception:
        rel_path = file_path.replace('\\', '/')

    try:
        conn = sqlite3.connect(DB_FILE)
        cursor = conn.cursor()
        cursor.execute("SELECT * FROM document_state WHERE path = ?", (rel_path,))
        row = cursor.fetchone()
        
        if not row:
            # Fallback check for raw path match
            cursor.execute("SELECT * FROM document_state WHERE path LIKE ?", (f"%{os.path.basename(file_path)}",))
            row = cursor.fetchone()
            
        conn.close()
    except Exception as e:
        safe_print(f"{RED}Error querying database: {e}{RESET}", is_error=True)
        sys.exit(1)

    if not row:
        safe_print(f"{RED}Error: File '{file_path}' (relativized: '{rel_path}') is not currently tracked by Neosis.{RESET}", is_error=True)
        sys.exit(1)

    # Database column mapping:
    # 0: doc_id, 1: path, 2: status, 3: current_stage, 4: retry_count, 5: last_error, 
    # 6: next_retry_at, 7: completed_chunks, 8: total_chunks, 9: checksum, 10: updated_at
    doc_id, path, status, current_stage, retry_count, last_error, next_retry_at, completed_chunks, total_chunks, checksum, updated_at = row

    completed_list = completed_chunks.split(",") if completed_chunks else []
    completed_count = len(completed_list)

    safe_print(f"\n{BOLD}{CYAN}Neosis Logs for document: {path}{RESET}")
    safe_print(f"{CYAN}{'=' * 60}{RESET}")
    safe_print(f"{BOLD}Document ID:{RESET}      {doc_id}")
    safe_print(f"{BOLD}Checksum:{RESET}         {checksum}")
    safe_print(f"{BOLD}State Status:{RESET}     {status}")
    safe_print(f"{BOLD}Current Stage:{RESET}    {current_stage}")
    safe_print(f"{BOLD}Retry Attempt:{RESET}    {retry_count} / 5")
    
    safe_print(f"{BOLD}Next Scheduled:{RESET}   {YELLOW}{format_timestamp(next_retry_at)}{RESET}")
    safe_print(f"{BOLD}Chunk Progress:{RESET}   {completed_count} / {total_chunks} completed successfully")
    safe_print(f"{BOLD}Last Updated:{RESET}     {format_timestamp(updated_at)}")
    safe_print(f"{CYAN}{'-' * 60}{RESET}")

    if last_error:
        safe_print(f"{BOLD}{RED}Last Ingest Error:{RESET}\n{RED}{last_error}{RESET}")
        safe_print(f"{CYAN}{'-' * 60}{RESET}")

    # Print retry log files if available
    log_file_path = os.path.join(DB_DIR, "retries", f"{doc_id}.log")
    if os.path.exists(log_file_path):
        safe_print(f"{BOLD}Recent Retry Tracebacks:{RESET}")
        try:
            with open(log_file_path, "r") as f:
                lines = f.readlines()
                # Print last 30 lines of detailed log to keep display concise
                safe_print("".join(lines[-30:]))
        except Exception as e:
            safe_print(f"{RED}Error reading log file: {e}{RESET}")
    else:
        safe_print(f"{BLUE}No persistent traceback logs are available for this document.{RESET}")

def main():
    parser = argparse.ArgumentParser(
        description="Neosis CLI tool for managing document lifecycle and state logs.",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    
    subparsers = parser.add_subparsers(dest="command", help="Neosis Subcommands")
    
    # Subcommand: init
    subparsers.add_parser("init", help="Initialize Neosis directory structure and SQLite state.db")
    
    # Subcommand: status
    subparsers.add_parser("status", help="Show pipeline and indexing status for all documents")
    
    # Subcommand: logs
    logs_parser = subparsers.add_parser("logs", help="View logs, scheduling, and error tracebacks for a document")
    logs_parser.add_argument("path", help="The absolute or relative path to the tracked document")
    
    args = parser.parse_args()
    
    if args.command == "init":
        init_cmd()
    elif args.command == "status":
        status_cmd()
    elif args.command == "logs":
        logs_cmd(args.path)
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
