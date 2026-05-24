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

prompt_menu() {
	local title=$1
	local text=$2
	shift 2

	if command -v whiptail >/dev/null 2>&1 && [ -r /dev/tty ] && [ -w /dev/tty ]; then
		whiptail --title "$title" --menu "$text" 15 76 5 "$@" \
			3>&1 1>/dev/tty 2>&3 </dev/tty
		return
	fi

	local choices=("$@")
	local index=1
	local i
	printf '%s\n%s\n' "$title" "$text" >&2
	for ((i = 0; i < ${#choices[@]}; i += 2)); do
		printf '  %d. %s - %s\n' "$index" "${choices[$i]}" "${choices[$((i + 1))]}" >&2
		index=$((index + 1))
	done
	printf 'Choose [1-%d]: ' "$((index - 1))" >&2
	read -r answer
	if ! [[ "$answer" =~ ^[0-9]+$ ]] || [ "$answer" -lt 1 ] || [ "$answer" -ge "$index" ]; then
		return 1
	fi
	printf '%s\n' "${choices[$(((answer - 1) * 2))]}"
}

confirm() {
	local title=$1
	local text=$2

	if command -v whiptail >/dev/null 2>&1 && [ -r /dev/tty ] && [ -w /dev/tty ]; then
		whiptail --title "$title" --yesno "$text" 20 90 >/dev/tty 2>&1 </dev/tty
		return
	fi

	local answer
	printf '%s\n%s [y/N]: ' "$title" "$text" >&2
	read -r answer
	[[ "$answer" =~ ^[Yy]$|^[Yy][Ee][Ss]$ ]]
}

prompt_push_action() {
	local text=$1

	prompt_menu \
		"Push" \
		"$text" \
		push "Run git push now" \
		diff "Show diff for HEAD~1..HEAD" \
		skip "Skip push"
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

view_last_commit_diff() {
	if [ -r /dev/tty ] && [ -w /dev/tty ]; then
		git diff HEAD~1 HEAD -- >/dev/tty </dev/tty
	else
		git diff HEAD~1 HEAD --
	fi
}

resolve_pull_conflict_with_codex() {
	local conflict_output

	conflict_output=$(mktemp "${TMPDIR:-/tmp}/cozazjeb-codex-conflict.XXXXXX")
	tmp_files+=("$conflict_output")

	printf '\nGit pull produced conflicts. Attempting to resolve them with Codex...\n'
	printf 'Conflicted files:\n'
	git diff --name-only --diff-filter=U | sed 's/^/  - /'

	if ! codex exec \
		-C "$repo_root" \
		--sandbox workspace-write \
		--output-last-message "$conflict_output" \
		'Git is currently stopped on a pull/rebase conflict. Inspect the conflicted files, resolve the conflict markers in the working tree, preserve the intended behavior from both sides where possible, and do not commit, push, reset, abort, or continue the rebase. After editing, report whether all conflict markers and unmerged paths are resolved.'; then
		echo "Codex failed while attempting to resolve the pull conflict."
		cat "$conflict_output" >&2
		return 1
	fi

	if has_unmerged_paths; then
		echo "Codex was not able to resolve all git conflicts."
		printf 'Still conflicted:\n'
		git diff --name-only --diff-filter=U | sed 's/^/  - /'
		cat "$conflict_output" >&2
		return 1
	fi

	if git diff --check; then
		git add --all
	else
		echo "Codex edits still contain conflict markers or whitespace errors."
		return 1
	fi

	if GIT_EDITOR=true git rebase --continue; then
		echo "Codex resolved the git conflict and the rebase continued successfully."
		return 0
	fi

	echo "Codex resolved the files, but git rebase --continue failed."
	return 1
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

if ! has_worktree_changes; then
	echo "No git changes to commit."
	exit 0
fi

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

if ! command -v codex >/dev/null 2>&1; then
	echo "codex command not found." >&2
	exit 1
fi

codex_output=$(mktemp "${TMPDIR:-/tmp}/cozazjeb-codex-commit.XXXXXX")
tmp_files+=("$codex_output")

codex exec \
	-C "$repo_root" \
	--sandbox read-only \
	--output-last-message "$codex_output" \
	'Use the repository-provided suggest-commit-message skill at doc/codex-skills/SKIL_suggest-commit-message/SKILL.md to propose a concise commit message for the currently staged changes. Return the final answer in the documented response shape, with the recommended commit message as the first fenced code block.' >/dev/null

commit_message=$(extract_commit_message "$codex_output")
if [ -z "$commit_message" ]; then
	echo "Could not parse a commit message from Codex output:" >&2
	cat "$codex_output" >&2
	exit 1
fi

git commit -m "$commit_message"

printf '\nCommitted with message:\n'
printf '%s\n' "$commit_message"

printf '\nCurrent git status:\n'
git status --short --branch

upstream=
if upstream=$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null); then
	echo "Fetching $upstream before push check..."
	git fetch --quiet
	read -r ahead behind < <(git rev-list --left-right --count HEAD..."$upstream")

	if [ "$behind" -gt 0 ]; then
		if confirm "Pull before push" "Upstream $upstream has $behind commit(s) not in this branch. Run git pull --rebase before push?"; then
			if ! git pull --rebase; then
				if has_unmerged_paths; then
					resolve_pull_conflict_with_codex || {
						echo "Aborting codex-commit because the git conflict was not resolved."
						exit 1
					}
				else
					echo "git pull --rebase failed without unmerged paths. Aborting."
					exit 1
				fi
			fi
		else
			echo "Skipping push because upstream has new commits."
			exit 0
		fi
	fi
else
	echo "No upstream branch is configured; git push will use Git's default behavior."
fi

push_summary=$(
	printf '1. git log message\n'
	git log -1 --format=%B
	printf '\n2. files changed in HEAD~1..HEAD\n'
	changed_files=$(git diff --name-status HEAD~1 HEAD --)
	if [ -n "$changed_files" ]; then
		printf '%s\n' "$changed_files"
	else
		printf 'No files changed in HEAD~1..HEAD.\n'
	fi
	printf '\nChoose the next action.'
)

while true; do
	push_choice=$(
		prompt_push_action "$push_summary"
	) || {
		echo "Push skipped."
		exit 0
	}

	case "$push_choice" in
		push)
			git push
			break
			;;
		diff)
			view_last_commit_diff
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
