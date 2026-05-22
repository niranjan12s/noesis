import os
import sys
import json
import sqlite3
import argparse
from datetime import datetime
import uuid
import base64
import hashlib
import urllib.request
import urllib.error
import getpass

# ANSI Escape Sequences for Premium Styling
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
BLUE = "\033[94m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"

DB_DIR = ".noesis"
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
    safe_print(f"{BOLD}{CYAN}Initializing Noesis Developer Environment...{RESET}")
    
    # 1. Create .noesis directory
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
            ".noesis/**"
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

    safe_print(f"\n{BOLD}{GREEN}★ Noesis successfully initialized!{RESET} You are ready to watch and index documents.")

def status_cmd():
    if not os.path.exists(DB_FILE):
        safe_print(f"{RED}Error: Noesis environment has not been initialized. Run 'noesis init' first.{RESET}", is_error=True)
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
        safe_print(f"{YELLOW}No documents are currently tracked in Noesis.{RESET}")
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
        safe_print(f"{RED}Error: Noesis environment has not been initialized. Run 'noesis init' first.{RESET}", is_error=True)
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
        safe_print(f"{RED}Error: File '{file_path}' (relativized: '{rel_path}') is not currently tracked by Noesis.{RESET}", is_error=True)
        sys.exit(1)

    # Database column mapping:
    # 0: doc_id, 1: path, 2: status, 3: current_stage, 4: retry_count, 5: last_error, 
    # 6: next_retry_at, 7: completed_chunks, 8: total_chunks, 9: checksum, 10: updated_at
    doc_id, path, status, current_stage, retry_count, last_error, next_retry_at, completed_chunks, total_chunks, checksum, updated_at = row

    completed_list = completed_chunks.split(",") if completed_chunks else []
    completed_count = len(completed_list)

    safe_print(f"\n{BOLD}{CYAN}Noesis Logs for document: {path}{RESET}")
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

def load_config():
    if os.path.exists(CONFIG_FILE):
        try:
            with open(CONFIG_FILE, "r") as f:
                return json.load(f)
        except Exception:
            pass
    return {}

def save_config(config):
    if not os.path.exists(DB_DIR):
        os.makedirs(DB_DIR)
    with open(CONFIG_FILE, "w") as f:
        json.dump(config, f, indent=2)

def get_machine_key():
    mac = str(uuid.getnode())
    return hashlib.sha256(mac.encode('utf-8')).digest()

def xor_crypt(data: bytes, key: bytes) -> bytes:
    return bytes(b ^ key[i % len(key)] for i, b in enumerate(data))

def encrypt_key(plain_text: str) -> str:
    if not plain_text:
        return ""
    key = get_machine_key()
    encrypted_bytes = xor_crypt(plain_text.encode('utf-8'), key)
    return base64.b64encode(encrypted_bytes).decode('utf-8')

def decrypt_key(cipher_text: str) -> str:
    if not cipher_text:
        return ""
    key = get_machine_key()
    encrypted_bytes = base64.b64decode(cipher_text.encode('utf-8'))
    decrypted_bytes = xor_crypt(encrypted_bytes, key)
    return decrypted_bytes.decode('utf-8')

def is_configured():
    config = load_config()
    return "llm" in config and "provider" in config["llm"]

def verify_connectivity(provider, model, api_key, base_url=None):
    safe_print(f"  {BLUE}⟳{RESET} Verifying connectivity to {BOLD}{provider}{RESET} using model {BOLD}{model}{RESET}...")
    try:
        if provider == "groq":
            url = "https://api.groq.com/openai/v1/chat/completions"
            payload = {
                "model": model,
                "messages": [{"role": "user", "content": "ping"}],
                "max_tokens": 5
            }
            req = urllib.request.Request(
                url,
                data=json.dumps(payload).encode('utf-8'),
                headers={
                    "Content-Type": "application/json",
                    "Authorization": f"Bearer {api_key}",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                },
                method="POST"
            )
        elif provider == "gemini":
            url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
            payload = {
                "contents": [{"parts": [{"text": "ping"}]}],
                "generationConfig": {"maxOutputTokens": 5}
            }
            req = urllib.request.Request(
                url,
                data=json.dumps(payload).encode('utf-8'),
                headers={
                    "Content-Type": "application/json",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                },
                method="POST"
            )
        elif provider == "ollama":
            url = f"{base_url.rstrip('/')}/api/generate"
            payload = {
                "model": model,
                "prompt": "ping",
                "stream": False,
                "options": {"num_predict": 5}
            }
            req = urllib.request.Request(
                url,
                data=json.dumps(payload).encode('utf-8'),
                headers={
                    "Content-Type": "application/json",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                },
                method="POST"
            )
        elif provider == "custom":
            url = f"{base_url.rstrip('/')}/chat/completions"
            payload = {
                "model": model,
                "messages": [{"role": "user", "content": "ping"}],
                "max_tokens": 5
            }
            headers = {
                "Content-Type": "application/json",
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
            }
            if api_key:
                headers["Authorization"] = f"Bearer {api_key}"
            req = urllib.request.Request(
                url,
                data=json.dumps(payload).encode('utf-8'),
                headers=headers,
                method="POST"
            )
        else:
            raise ValueError(f"Unknown provider: {provider}")

        with urllib.request.urlopen(req, timeout=10) as response:
            if response.status == 200:
                safe_print(f"  {GREEN}✓{RESET} Connection verified successfully!")
                return True
            else:
                safe_print(f"  {RED}✗{RESET} Validation returned status {response.status}", is_error=True)
                return False
    except urllib.error.HTTPError as e:
        safe_print(f"  {RED}✗{RESET} Connection failed with HTTP Error {e.code}: {e.reason}", is_error=True)
        try:
            err_body = e.read().decode('utf-8')
            safe_print(f"    Details: {err_body[:200]}", is_error=True)
        except Exception:
            pass
        return False
    except urllib.error.URLError as e:
        safe_print(f"  {RED}✗{RESET} Connection failed: Unreachable host/network ({e.reason})", is_error=True)
        return False
    except Exception as e:
        safe_print(f"  {RED}✗{RESET} Connection failed: {e}", is_error=True)
        return False

def setup_cmd(force=False):
    safe_print(f"\n{BOLD}{CYAN}=================================================={RESET}")
    safe_print(f"{BOLD}{CYAN}       Noesis LLM Configuration Setup Wizard      {RESET}")
    safe_print(f"{BOLD}{CYAN}=================================================={RESET}")

    if is_configured() and not force:
        config = load_config()
        llm = config["llm"]
        safe_print(f"\n{GREEN}✓ Noesis is already configured!{RESET}")
        safe_print(f"  Provider:  {BOLD}{llm.get('provider')}{RESET}")
        safe_print(f"  Model:     {BOLD}{llm.get('model')}{RESET}")
        if llm.get("base_url"):
            safe_print(f"  Base URL:  {BOLD}{llm.get('base_url')}{RESET}")
        safe_print(f"\nUse 'python noesis.py setup --force' to reconfigure.\n")
        return

    while True:
        safe_print(f"\n{BOLD}Select LLM Provider:{RESET}")
        safe_print("  1) Groq (Recommended)")
        safe_print("  2) Gemini")
        safe_print("  3) Ollama (Local)")
        safe_print("  4) Custom (OpenAI-compatible)")
        
        provider_choice = input("Enter choice [1-4, default: 1]: ").strip()
        if not provider_choice:
            provider_choice = "1"
        
        provider_map = {"1": "groq", "2": "gemini", "3": "ollama", "4": "custom"}
        provider = provider_map.get(provider_choice)
        if not provider:
            safe_print(f"{RED}Invalid choice. Please select 1, 2, 3, or 4.{RESET}", is_error=True)
            continue

        model = ""
        base_url = ""
        api_key = ""

        if provider == "groq":
            safe_print(f"\n{BOLD}Select Groq Model:{RESET}")
            safe_print("  1) llama-3.1-8b-instant (Recommended)")
            safe_print("  2) llama-3.3-70b-specdec")
            safe_print("  3) mixtral-8x7b-32768")
            model_choice = input("Enter choice [1-3, default: 1] or type custom model identifier: ").strip()
            if not model_choice or model_choice == "1":
                model = "llama-3.1-8b-instant"
            elif model_choice == "2":
                model = "llama-3.3-70b-specdec"
            elif model_choice == "3":
                model = "mixtral-8x7b-32768"
            else:
                model = model_choice
            
            api_key = getpass.getpass("Enter Groq API Key: ").strip()

        elif provider == "gemini":
            safe_print(f"\n{BOLD}Select Gemini Model:{RESET}")
            safe_print("  1) gemini-1.5-flash (Recommended)")
            safe_print("  2) gemini-1.5-pro")
            safe_print("  3) gemini-2.5-flash")
            model_choice = input("Enter choice [1-3, default: 1] or type custom model identifier: ").strip()
            if not model_choice or model_choice == "1":
                model = "gemini-1.5-flash"
            elif model_choice == "2":
                model = "gemini-1.5-pro"
            elif model_choice == "3":
                model = "gemini-2.5-flash"
            else:
                model = model_choice
            
            api_key = getpass.getpass("Enter Gemini API Key: ").strip()

        elif provider == "ollama":
            safe_print(f"\n{BOLD}Select Ollama Model:{RESET}")
            safe_print("  1) llama3.2:1b (Recommended)")
            safe_print("  2) llama3:8b")
            safe_print("  3) mistral")
            model_choice = input("Enter choice [1-3, default: 1] or type custom model identifier: ").strip()
            if not model_choice or model_choice == "1":
                model = "llama3.2:1b"
            elif model_choice == "2":
                model = "llama3:8b"
            elif model_choice == "3":
                model = "mistral"
            else:
                model = model_choice

            base_url = input("Enter Ollama Base URL [default: http://localhost:11434]: ").strip()
            if not base_url:
                base_url = "http://localhost:11434"

        elif provider == "custom":
            model = input("Enter Model Name (e.g. deepseek-chat): ").strip()
            while not model:
                model = input("Model name is required. Enter Model Name: ").strip()
            
            base_url = input("Enter Custom Base URL (e.g. http://localhost:8000/v1): ").strip()
            while not base_url:
                base_url = input("Base URL is required. Enter Custom Base URL: ").strip()
            
            api_key = getpass.getpass("Enter API Key (press Enter if none needed): ").strip()

        verified = verify_connectivity(provider, model, api_key, base_url)
        if not verified:
            proceed = input(f"\n{YELLOW}Warning: Connectivity verification failed.{RESET}\nDo you want to proceed and save this configuration anyway? (y/n) [default: n]: ").strip().lower()
            if proceed != 'y':
                safe_print("Let's re-enter the configurations.")
                continue

        encrypted_key = encrypt_key(api_key)
        config = load_config()
        
        if "include" not in config:
            config["include"] = ["**/*.md", "docs/**/*.md", "README.md"]
        if "exclude" not in config:
            config["exclude"] = ["node_modules/**", ".git/**", "dist/**", "build/**", ".noesis/**"]
            
        config["llm"] = {
            "provider": provider,
            "model": model,
            "api_key": encrypted_key,
            "base_url": base_url
        }
        
        save_config(config)
        safe_print(f"\n{BOLD}{GREEN}★ LLM Configuration successfully saved and encrypted!{RESET}")
        safe_print(f"  Stored in: {BOLD}{CONFIG_FILE}{RESET}")
        break

def get_env_cmd():
    if not is_configured():
        sys.exit(1)
    
    config = load_config()
    llm = config["llm"]
    provider = llm.get("provider", "")
    model = llm.get("model", "")
    encrypted_key = llm.get("api_key", "")
    base_url = llm.get("base_url", "")

    api_key = decrypt_key(encrypted_key)

    print(f"NOESIS_LLM_PROVIDER={provider}")
    print(f"NOESIS_LLM_MODEL={model}")
    if provider == "groq":
        print(f"GROQ_API_KEY={api_key}")
    elif provider == "gemini":
        print(f"GEMINI_API_KEY={api_key}")
    elif provider == "custom":
        print(f"OPENAI_API_KEY={api_key}")
        print(f"OPENAI_BASE_URL={base_url}")
    elif provider == "ollama":
        print(f"NOESIS_LLM_BASE_URL={base_url}")

def main():
    parser = argparse.ArgumentParser(
        description="Noesis CLI tool for managing document lifecycle and state logs.",
        formatter_class=argparse.RawDescriptionHelpFormatter
    )
    
    subparsers = parser.add_subparsers(dest="command", help="Noesis Subcommands")
    
    # Subcommand: init
    subparsers.add_parser("init", help="Initialize Noesis directory structure and SQLite state.db")
    
    # Subcommand: status
    subparsers.add_parser("status", help="Show pipeline and indexing status for all documents")
    
    # Subcommand: logs
    logs_parser = subparsers.add_parser("logs", help="View logs, scheduling, and error tracebacks for a document")
    logs_parser.add_argument("path", help="The absolute or relative path to the tracked document")

    # Subcommand: setup
    setup_parser = subparsers.add_parser("setup", help="Interactive LLM credentials setup wizard")
    setup_parser.add_argument("--force", action="store_true", help="Force configuration run even if already configured")

    # Subcommand: get-env
    subparsers.add_parser("get-env", help="Hidden helper to export decrypted LLM credentials")
    
    args = parser.parse_args()
    
    if args.command == "init":
        init_cmd()
    elif args.command == "status":
        status_cmd()
    elif args.command == "logs":
        logs_cmd(args.path)
    elif args.command == "setup":
        setup_cmd(args.force)
    elif args.command == "get-env":
        get_env_cmd()
    else:
        parser.print_help()

if __name__ == "__main__":
    main()
