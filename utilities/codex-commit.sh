#!/usr/bin/env bash
set -euo pipefail

repo_root=$(git rev-parse --show-toplevel)
cd "$repo_root"

tmp_files=()
cleanup() {
	if [ "${#tmp_files[@]}" -gt 0 ]; then
		rm -f "${tmp_files[@]}"
	fi
}
trap cleanup EXIT

ui_fallback_explained=0

# COMMIT_AGENT selects the CLI that writes commit messages and resolves pull
# conflicts: "codex" (default) or "claude". utilities/claude-commit.sh sets it.
commit_agent=${COMMIT_AGENT:-codex}
case "$commit_agent" in
	codex) agent_label=Codex ;;
	claude) agent_label=Claude ;;
	*)
		echo "Unsupported COMMIT_AGENT '$commit_agent'; use codex or claude." >&2
		exit 1
		;;
esac
script_name="${commit_agent}-commit"

agent_command=()
resolve_codex_command() {
	if [ -n "${CODEX_BIN:-}" ] && [ -x "${CODEX_BIN}" ]; then
		agent_command=("${CODEX_BIN}")
		return 0
	fi

	if command -v codex >/dev/null 2>&1; then
		agent_command=(codex)
		return 0
	fi

	local candidate
	for candidate in \
		"/Applications/Codex.app/Contents/Resources/codex" \
		"$HOME/Applications/Codex.app/Contents/Resources/codex" \
		"/Applications/Codex.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex" \
		"$HOME/Applications/Codex.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex" \
		"/Applications/ChatGPT.app/Contents/Resources/codex" \
		"$HOME/Applications/ChatGPT.app/Contents/Resources/codex" \
		"/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex" \
		"$HOME/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"; do
		if [ -x "$candidate" ]; then
			agent_command=("$candidate")
			return 0
		fi
	done

	return 1
}

resolve_claude_command() {
	if [ -n "${CLAUDE_BIN:-}" ] && [ -x "${CLAUDE_BIN}" ]; then
		agent_command=("${CLAUDE_BIN}")
		return 0
	fi

	if command -v claude >/dev/null 2>&1; then
		agent_command=(claude)
		return 0
	fi

	local candidate
	for candidate in \
		"$HOME/.local/bin/claude" \
		"$HOME/.claude/local/claude" \
		"/opt/homebrew/bin/claude" \
		"/usr/local/bin/claude"; do
		if [ -x "$candidate" ]; then
			agent_command=("$candidate")
			return 0
		fi
	done

	return 1
}

resolve_agent_command() {
	case "$commit_agent" in
		codex) resolve_codex_command ;;
		claude) resolve_claude_command ;;
	esac
}

agent_install_hint() {
	case "$commit_agent" in
		codex) printf "Install the Codex CLI, set CODEX_BIN, or add Codex.app/ChatGPT.app's bundled CLI to PATH." ;;
		claude) printf 'Install Claude Code (https://claude.com/claude-code), set CLAUDE_BIN, or add claude to PATH.' ;;
	esac
}

# Run the agent non-interactively. $1 is "read-only" or "workspace-write",
# $2 is the file that receives the final message, $3 is the prompt.
run_agent() {
	local mode=$1
	local output_file=$2
	local prompt=$3

	if [ "$commit_agent" = codex ]; then
		"${agent_command[@]}" exec \
			-C "$repo_root" \
			--sandbox "$mode" \
			--output-last-message "$output_file" \
			"$prompt"
		return
	fi

	local read_tools="Read,Grep,Glob,Bash(git status:*),Bash(git diff:*),Bash(git log:*),Bash(git show:*),Bash(git ls-files:*)"
	local denied_tools="Bash(git commit:*),Bash(git push:*),Bash(git reset:*),Bash(git rebase:*),Bash(git checkout:*),Bash(git restore:*),Bash(git stash:*)"
	local claude_args=(-p --output-format text --disallowedTools "$denied_tools")

	if [ "$mode" = workspace-write ]; then
		claude_args+=(--permission-mode acceptEdits --allowedTools "$read_tools,Edit,Write")
	else
		claude_args+=(--permission-mode default --allowedTools "$read_tools")
	fi

	# --allowedTools/--disallowedTools take variadic values, so the prompt goes on stdin.
	(cd "$repo_root" && printf '%s\n' "$prompt" | "${agent_command[@]}" "${claude_args[@]}") >"$output_file"
}

terminal_ui_unavailable_reason() {
	if [ ! -e /dev/tty ]; then
		printf 'no controlling terminal is available'
		return 0
	fi

	if [ ! -r /dev/tty ] || [ ! -w /dev/tty ]; then
		printf '/dev/tty is not readable and writable'
		return 0
	fi

	if [ -z "${TERM:-}" ]; then
		printf 'TERM is not set'
		return 0
	fi

	if [ "${TERM:-}" = "dumb" ]; then
		printf 'TERM=dumb'
		return 0
	fi

	if ! infocmp "$TERM" >/dev/null 2>&1; then
		printf 'no terminfo entry exists for TERM=%s' "$TERM"
		return 0
	fi

	return 1
}

can_use_terminal_ui() {
	! terminal_ui_unavailable_reason >/dev/null
}

can_use_dialog() {
	command -v dialog >/dev/null 2>&1 && can_use_terminal_ui
}

can_use_whiptail() {
	command -v whiptail >/dev/null 2>&1 && can_use_terminal_ui
}

explain_plain_prompt_fallback() {
	local reason

	if [ "$ui_fallback_explained" -eq 1 ]; then
		return
	fi

	ui_fallback_explained=1

	if reason=$(terminal_ui_unavailable_reason); then
		printf 'Using plain terminal prompts because the dialog UI is unavailable: %s.\n' "$reason" >&2
		return
	fi

	if ! command -v dialog >/dev/null 2>&1 && ! command -v whiptail >/dev/null 2>&1; then
		printf 'Using plain terminal prompts because neither dialog nor whiptail is on PATH.\n' >&2
	fi
}

read_prompt_answer() {
	local variable_name=$1

	if [ -r /dev/tty ] && [ -w /dev/tty ]; then
		IFS= read -r "$variable_name" </dev/tty
		return
	fi

	IFS= read -r "$variable_name"
}

prompt_menu() {
	local title=$1
	local text=$2
	shift 2

	if can_use_dialog; then
		dialog --stdout --title "$title" --menu "$text" 15 76 5 "$@" \
			2>/dev/tty </dev/tty
		return
	fi

	if can_use_whiptail; then
		whiptail --title "$title" --menu "$text" 15 76 5 "$@" \
			3>&1 1>/dev/tty 2>&3 </dev/tty
		return
	fi

	explain_plain_prompt_fallback

	local choices=("$@")
	local index=1
	local i
	printf '%s\n%s\n' "$title" "$text" >&2
	for ((i = 0; i < ${#choices[@]}; i += 2)); do
		printf '  %d. %s - %s\n' "$index" "${choices[$i]}" "${choices[$((i + 1))]}" >&2
		index=$((index + 1))
	done
	printf 'Choose [1-%d]: ' "$((index - 1))" >&2
	if ! read_prompt_answer answer; then
		printf '\nNo interactive input was available.\n' >&2
		return 1
	fi
	if ! [[ "$answer" =~ ^[0-9]+$ ]] || [ "$answer" -lt 1 ] || [ "$answer" -ge "$index" ]; then
		return 1
	fi
	printf '%s\n' "${choices[$(((answer - 1) * 2))]}"
}

confirm() {
	local title=$1
	local text=$2

	if can_use_dialog; then
		dialog --title "$title" --yesno "$text" 20 90 >/dev/tty 2>&1 </dev/tty
		return
	fi

	if can_use_whiptail; then
		whiptail --title "$title" --yesno "$text" 20 90 >/dev/tty 2>&1 </dev/tty
		return
	fi

	explain_plain_prompt_fallback

	local answer
	printf '%s\n%s [y/N]: ' "$title" "$text" >&2
	if ! read_prompt_answer answer; then
		printf '\nNo interactive input was available.\n' >&2
		return 1
	fi
	[[ "$answer" =~ ^[Yy]$|^[Yy][Ee][Ss]$ ]]
}

whiptail_supports_extra_button() {
	whiptail --help 2>&1 | grep -q -- '--extra-button'
}

prompt_push_action() {
	local text=$1
	local status

	if can_use_dialog; then
		dialog \
			--title "Push" \
			--yes-button "Push" \
			--extra-button \
			--extra-label "Show diff" \
			--no-button "Cancel" \
			--yesno "$text" 20 90 \
			>/dev/tty 2>&1 </dev/tty
		status=$?
		case "$status" in
			0) printf 'push\n' ;;
			1) printf 'skip\n' ;;
			3) printf 'diff\n' ;;
			*) return 1 ;;
		esac
		return
	fi

	if can_use_whiptail; then
		if whiptail_supports_extra_button; then
			whiptail \
				--title "Push" \
				--yes-button "Push" \
				--extra-button \
				--extra-label "Show diff" \
				--no-button "Cancel" \
				--yesno "$text" 20 90 \
				>/dev/tty 2>&1 </dev/tty
			status=$?
			case "$status" in
				0) printf 'push\n' ;;
				1) printf 'skip\n' ;;
				3) printf 'diff\n' ;;
				*) return 1 ;;
			esac
			return
		fi

		whiptail \
			--title "Push" \
			--yes-button "Push" \
			--no-button "Show diff" \
			--yesno "$text

Press Esc to cancel." 22 90 \
			>/dev/tty 2>&1 </dev/tty
		status=$?
		case "$status" in
			0) printf 'push\n' ;;
			1) printf 'diff\n' ;;
			*) return 1 ;;
		esac
		return
	fi

	prompt_menu \
		"Push" \
		"$text" \
		push "Run git push now" \
		diff "Show diff for HEAD~1..HEAD" \
		skip "Cancel"
}

has_worktree_changes() {
	! git diff --quiet || ! git diff --cached --quiet || [ -n "$(git ls-files --others --exclude-standard)" ]
}

has_version_change() {
	git diff -- build.gradle.kts | grep -Eq '^[-+]version[[:space:]]*=' ||
		git diff --cached -- build.gradle.kts | grep -Eq '^[-+]version[[:space:]]*='
}

has_unmerged_paths() {
	[ -n "$(git diff --name-only --diff-filter=U)" ]
}

rebase_in_progress() {
	[ -d "$(git rev-parse --git-path rebase-merge)" ] ||
		[ -d "$(git rev-parse --git-path rebase-apply)" ]
}

view_last_commit_diff() {
	local diff_base=${1:-HEAD~1}

	if [ -r /dev/tty ] && [ -w /dev/tty ]; then
		git diff "$diff_base" HEAD -- >/dev/tty </dev/tty
	else
		git diff "$diff_base" HEAD --
	fi
}

resolve_pull_conflict_with_agent() {
	local conflict_output

	conflict_output=$(mktemp "${TMPDIR:-/tmp}/cozadzban-${commit_agent}-conflict.XXXXXX")
	tmp_files+=("$conflict_output")

	printf '\nGit pull produced conflicts. Attempting to resolve them with %s...\n' "$agent_label"
	printf 'Conflicted files:\n'
	git diff --name-only --diff-filter=U | sed 's/^/  - /'

	if ! run_agent workspace-write "$conflict_output" \
		'Git is currently stopped on a pull conflict, either during a rebase or while applying an autostash after the rebase. Inspect the conflicted files, resolve the conflict markers in the working tree, preserve the intended behavior from both sides where possible, and do not commit, push, reset, abort, or continue the rebase. After editing, report whether all conflict markers and unmerged paths are resolved.'; then
		echo "$agent_label failed while attempting to resolve the pull conflict."
		cat "$conflict_output" >&2
		return 1
	fi

	if has_unmerged_paths; then
		echo "$agent_label was not able to resolve all git conflicts."
		printf 'Still conflicted:\n'
		git diff --name-only --diff-filter=U | sed 's/^/  - /'
		cat "$conflict_output" >&2
		return 1
	fi

	if git diff --check; then
		git add --all
	else
		echo "$agent_label edits still contain conflict markers or whitespace errors."
		return 1
	fi

	if ! rebase_in_progress; then
		echo "$agent_label resolved the git conflict."
		return 0
	fi

	if GIT_EDITOR=true git rebase --continue; then
		echo "$agent_label resolved the git conflict and the rebase continued successfully."
		return 0
	fi

	echo "$agent_label resolved the files, but git rebase --continue failed."
	return 1
}

pull_rebase_upstream() {
	if ! git pull --rebase --autostash; then
		if has_unmerged_paths; then
			if ! resolve_agent_command; then
				echo "git pull produced conflicts, but the $commit_agent command was not found." >&2
				echo "$(agent_install_hint) Or resolve the conflicts manually before rerunning $script_name." >&2
				exit 1
			fi

			resolve_pull_conflict_with_agent || {
				echo "Aborting $script_name because the git conflict was not resolved."
				exit 1
			}
		else
			echo "git pull --rebase --autostash failed without unmerged paths. Aborting."
			exit 1
		fi
	fi
}

extract_commit_message() {
	local output_file=$1
	local message

	message=$(
		awk '
			/^```/ {
				if (!seen) {
					seen = 1
					in_block = 1
					next
				}
				if (in_block) {
					exit
				}
			}
			in_block {
				print
			}
		' "$output_file" | sed '/^[[:space:]]*$/d; s/[[:space:]]*$//'
	)

	if [ -z "$message" ]; then
		message=$(sed '/^[[:space:]]*$/d; /^```/d; s/[[:space:]]*$//' "$output_file" | head -n 1)
	fi

	printf '%s\n' "$message"
}

sync_upstream_before_push() {
	upstream=
	ahead=0
	behind=0

	if upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null); then
		echo "Fetching $upstream before push check..."
		git fetch --quiet
		read -r ahead behind < <(git rev-list --left-right --count HEAD..."$upstream")

		if [ "$behind" -gt 0 ]; then
			if confirm "Pull before push" "Upstream $upstream has $behind commit(s) not in this branch. Run git pull --rebase before push?"; then
				pull_rebase_upstream

				read -r ahead behind < <(git rev-list --left-right --count HEAD..."$upstream")
			else
				echo "Skipping push because upstream has new commits."
				exit 0
			fi
		fi
	else
		echo "No upstream branch is configured; git push will use Git's default behavior."
	fi
}

sync_upstream_before_commit() {
	local upstream
	local ahead
	local behind

	if ! upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null); then
		echo "No upstream branch is configured; skipping pre-commit update check."
		return 0
	fi

	echo "Fetching $upstream before commit check..."
	git fetch --quiet
	read -r ahead behind < <(git rev-list --left-right --count HEAD..."$upstream")

	if [ "$behind" -eq 0 ]; then
		return 0
	fi

	if confirm "Update before commit" "Upstream $upstream has $behind commit(s) not in this branch. Update with git pull --rebase --autostash before staging, bumping, and committing?"; then
		pull_rebase_upstream
	else
		echo "Continuing without upstream updates."
	fi
}

prompt_for_push() {
	local diff_base="HEAD~1"
	local diff_label="HEAD~1..HEAD"
	local changed_files
	local push_summary
	local push_choice

	if [ -n "${upstream:-}" ] && [ "${ahead:-0}" -eq 0 ]; then
		echo "No unpushed commits found."
		return 0
	fi

	if [ -n "${upstream:-}" ]; then
		diff_base="$upstream"
		diff_label="$upstream..HEAD"
	fi

	push_summary=$(
		printf '1. unpushed commit(s)\n'
		if [ -n "${upstream:-}" ]; then
			git log --oneline "$upstream"..HEAD
		else
			git log -1 --oneline
		fi
		printf '\n2. files changed in %s\n' "$diff_label"
		changed_files=$(git diff --name-status "$diff_base" HEAD --)
		if [ -n "$changed_files" ]; then
			printf '%s\n' "$changed_files"
		else
			printf 'No files changed in %s.\n' "$diff_label"
		fi
		printf '\nChoose the next action.'
	)

	while true; do
		push_choice=$(
			prompt_push_action "$push_summary"
		) || {
			echo "Push skipped."
			return 0
		}

		case "$push_choice" in
			push)
				git push
				break
				;;
			diff)
				view_last_commit_diff "$diff_base"
				;;
			skip)
				echo "Push skipped."
				break
				;;
			*)
				echo "Unexpected choice: $push_choice" >&2
				exit 1
				;;
		esac
	done
}

if ! has_worktree_changes; then
	echo "No git changes to commit."
	sync_upstream_before_push
	prompt_for_push
	exit 0
fi

if ! resolve_agent_command; then
	echo "$commit_agent command not found. $(agent_install_hint)" >&2
	exit 1
fi

sync_upstream_before_commit

if ! has_version_change; then
	choice=$(
		prompt_menu \
			"Version bump" \
			"No version change was found in git diff. Choose whether to bump before committing." \
			patch "Run make bump-patch" \
			minor "Run make bump-minor" \
			none "Continue without bumping"
	) || {
		echo "Cancelled."
		exit 1
	}

	case "$choice" in
		patch) make bump-patch ;;
		minor) make bump-minor ;;
		none) ;;
		*)
			echo "Unexpected choice: $choice" >&2
			exit 1
			;;
	esac
fi

git add --all

if git diff --cached --quiet; then
	echo "No staged changes to commit."
	exit 0
fi

agent_output=$(mktemp "${TMPDIR:-/tmp}/cozadzban-${commit_agent}-commit.XXXXXX")
tmp_files+=("$agent_output")

run_agent read-only "$agent_output" \
	'Use the repository-provided suggest-commit-message skill at doc/codex-skills/SKIL_suggest-commit-message/SKILL.md to propose a concise commit message for the currently staged changes. Return the final answer in the documented response shape, with the recommended commit message as the first fenced code block.' >/dev/null

commit_message=$(extract_commit_message "$agent_output")
if [ -z "$commit_message" ]; then
	echo "Could not parse a commit message from $agent_label output:" >&2
	cat "$agent_output" >&2
	exit 1
fi

git commit -m "$commit_message"

printf '\nCommitted with message:\n'
printf '%s\n' "$commit_message"

printf '\nCurrent git status:\n'
git status --short --branch

sync_upstream_before_push
prompt_for_push
