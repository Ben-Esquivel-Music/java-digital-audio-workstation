---
title: "Log and Toast Text Neutralize Control Characters From Names and Messages"
labels: ["bug", "logging", "notifications", "error-handling", "reliability"]
---

# Log and Toast Text Neutralize Control Characters From Names and Messages

## Motivation

Story 336 writes every failure that reaches its handlers to a rotating log file, `daw-*.log`, and shows it as an ERROR toast; when the failure funnel itself fails, it writes a last-resort record to standard error and to `daw-last-resort.log` (`FAILURE_SURFACING_DESIGN_BOOK.md` §4.1 and §4.2). Its review made every line of `daw-*.log`, of the console handler and of the last-resort record keep only the line breaks its writer adds. Each of the seven characters `\R` matches inside a line (LF, CR, VT, FF, NEL, LS and PS) is written as its escape, `\u000A` for LF, so no value can split a record or forge one (`GuardedThrowables.oneLine` and `endLine`; story 336, ruling R992-2). Other characters that plugins, the file system and the operating system control still reach those files and the toast's copy as they are (story 336, ruling R992-6 and its Known limits):

- **Control characters in the log files.** `GuardedThrowables.isLineBreak` tests only the seven line breaks (`c == 0x0A || c == 0x0D || c == 0x0B || c == 0x0C || c == 0x85 || c == 0x2028 || c == 0x2029`). Any other C0 or C1 control character, such as ESC (U+001B), in a thread's, logger's or plugin's name, a level, a class name, a frame or a message reaches `daw-*.log`, the console and the last-resort record unchanged. A terminal that shows the file with `cat` or `tail` interprets an ESC sequence: it can recolour the text, move the cursor or overwrite what is already on screen, so the reader sees text the file does not hold.
- **The header's own delimiters.** A record's header is `<timestamp> <level padded to 7> [<thread>] <logger>: <message>` (story 336's Resolution), and a last-resort record's is `<timestamp> LAST-RESORT [<thread>] the failure funnel could not handle a failure` (`LastResortLog.header`, in daw-app's `diagnostics` package). A `]` in a thread's name, or `: ` in a logger's name, ends that field early for a reader that splits the header at its delimiters, so the thread, the logger and the message are ambiguous to a parser.
- **Line breaks and control characters in a toast's copy.** `FailureCopy.taskName` turns a background thread's name into the task a toast names (`Internal error in the background task “{0}”: {1}. Its work may be incomplete.`): it strips a leading `daw-` and a trailing `-<digits>`, turns `-` and `_` into spaces and trims the ends (`FailureCopy.java:245-258`). A line break or a control character inside the name stays, and the toast's message and its Notifications history entry carry it. The reason a toast quotes is the exception message's first line, cut at the first LF or CR (`GuardedThrowables.reason`, through its `firstLine`), so a VT, FF, NEL, LS, PS or control character before that point reaches the copy too. How the bar and a history pill render such a character has not been checked.

## Goals

- **One rule for which characters a log line or a toast's copy may hold.** With the studio engineer, this story decides which characters other than the seven line breaks are escaped and how: for example every C0 and C1 control character, written in story 336's `\uXXXX` form or another. The rule names what a tab inside a value becomes, since a tab at a line's start marks a record's following line.
- **Both log formats apply it in one place.** `daw-*.log`, the console handler and the last-resort record apply the rule where story 336 applies its line-break escape, in `GuardedThrowables.endLine` and `oneLine`, so every slot of both formats, current or future, is covered by construction.
- **The header's fields are unambiguous.** A `]` in a thread's name and `: ` in a logger's name no longer end their fields early. The studio engineer rules how, and `grep -E '^[0-9]{4}-'` still lists the records.
- **A toast's copy and its history entry hold no line break or control character from a name or a message.** The copy applies the rule to every name it takes from a thread, a plugin or the file system, and to the reason it quotes, so the toast and its Notifications history entry read as one line.

## Acceptance Criteria

1. A character the ruling names, in any slot of either log format, is written in the ruled form, and every record is still one header line followed by lines that begin with a tab.
2. A thread's name holding `]` and a logger's name holding `: ` leave the header's fields unambiguous.
3. A background thread's name, a declared plugin's name or an exception message holding a line break or a control character gives a toast and a history entry without it.
4. Text without any such character is unchanged: the exact-text assertions of story 336's tests stay green.

## Goals — Tests

These tests are binding acceptance criteria. They run headless, and none of them assumes a non-Windows environment.

- For each character the ruling names, one record fed through every slot of `LogLineFormatter` (the thread, the level, the logger, the message, and the exception's class, message and frames, its cause and its suppressed exceptions included), its head and its fallback: one record start, every following line begins with a tab, and the character appears only in its ruled form.
- The same for `LastResortLog`'s record: the thread, the activity, the class names, the messages and the frames; standard error holds the same text as the file.
- A header test: a thread named with `]` and a logger named with `: ` give a header whose fields read back as they were written, under the ruled form.
- A toast-copy test: a background thread whose name holds a line break and an ESC fails; the toast's message and its history entry hold neither. The same for a declared plugin's name and for an exception message.
- Fault check, run once, turning a test red: the new characters removed from the rule in `GuardedThrowables`, so a control character reaches the file.

## Non-Goals

- The seven line breaks: story 336 escapes them already (ruling R992-2), and a message's CR, LF and CRLF keep beginning tab-indented continuation lines in the log file.
- Changing a name at its source, such as `DawTaskRunner`'s thread label: story 336 fixed the formats and left names as they are (ruling R992-2).
- The lines the log sink prints straight to standard error outside both formats, when the log file cannot be opened or written: story 336 declined them (ruling R992-7).
- A plugin that writes the log files itself: a plugin runs in the application's process, so the escapes guard against accidental values, not against a hostile plugin (story 336, ruling R992-2).

## Technical Notes

- daw-app, package `app.diagnostics`: `GuardedThrowables` (`oneLine`, `endLine` and `isLineBreak`; since story 336, the one place that keeps a value on its line), `LogLineFormatter` (`appendHeader`, `threadOf` and `fallback`), `LastResortLog` (`header` and `threadName`), `FailureFunnel` (`logFact` and `logTail` pass their whole message through `oneLine`; story 336, ruling R992-3) and `FailureCopy` (`taskName`, and the copies that quote a declared subject or a reason).
- Story 336's tests share their line breaks through `GuardedThrowablesTest.LineBreak`, an enum of each break and its escape that `LastResortLogTest` and `LogLineFormatterTest` read through `@EnumSource`, and its sentinel `FailureSpineWiringSourceTest.everyLineTheLogWritersEndPassesThroughEndLine` pins that the three writers end every line through `endLine`. The characters this story adds can follow the same pattern.
- Java source pitfall (JLS §3.3): a backslash followed by `u` and four hex digits is translated before the compiler reads the source, in comments and Javadoc too. Compare characters as int literals (`c == 0x1B`), build an escape from `'\\'` and `'u'`, write `&#92;u001B` in Javadoc and `"\\u001B"` in a test's string (story 336, ruling R992-2).
- Book: `FAILURE_SURFACING_DESIGN_BOOK.md` §4.2 (the log sink and its format) and §5.4 (notification discipline, "Message names the thing").
- Cross-refs: story 336 (rulings R992-2, R992-3, R992-6 and R992-7, and its Known limits, "Control characters in logged names and messages").

## Status

- Deferred
