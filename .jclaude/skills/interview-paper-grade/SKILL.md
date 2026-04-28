---
description: Grade a completed interview paper batch by comparing the candidate response file against the generated paper and answer sheet.
when_to_use: Use when the user has already generated an interview-paper batch and wants grading, scoring, and feedback from the batch directory.
argument-hint: "\"/absolute/path/to/试卷批次目录\""
arguments: batch_dir
---
# Interview Paper Grade

Treat `$batch_dir` as the absolute path to an interview-paper batch directory.

If `$batch_dir` is empty, not an absolute path, or does not point to a readable directory, stop and ask the user only for the correct absolute batch directory path.

If the path contains spaces, treat the quoted path as the real argument value.

## Goal

Grade one completed interview-paper batch using the files already stored in that batch directory.

Prefer the simplest workflow: infer the paper, candidate response file, and answer file automatically from the batch directory.

## Required File Discovery

Inside `$batch_dir`, automatically locate and use these files:

1. `01_*_试卷.md`
2. `02_*_我的作答.md`
3. `03_*_试卷答案.md`

If any of the 3 files are missing, clearly tell the user which file is missing and stop.

## Grading Rules

Compare:

- the paper questions
- the standard answers
- the candidate's actual responses in `02_*_我的作答.md`

Treat placeholder labels such as `我的答案：`, `我的作答：`, separators, retained question text, and other template scaffolding as non-answer content.

If the candidate left the answer area blank, mark that question as 未作答.

Grade by semantic correctness, not by exact wording match.

If the candidate uses different wording but conveys the correct meaning, score it as correct or basically correct.

If the direction is right but key points are missing, deduct points and explain which points are missing.

If there are clear factual errors, concept confusion, or logic issues, point them out directly.

Follow the scoring scheme from `01_*_试卷.md` as the highest-priority grading basis.

Use this scoring policy:

- if `01_*_试卷.md` defines section scores, per-question scores, or a total score, reuse that scheme directly
- do not silently invent a different point distribution when the paper already defines one
- if the paper gives only partial scoring information, complete the missing details in the most natural way while preserving the stated totals
- if the paper provides no usable scoring scheme, create a reasonable temporary scoring scheme before grading and state that scheme clearly in the report

Keep scoring internally consistent across:

- each question's score
- each section subtotal
- the final total score

When matching answers to grading items, use question numbering and structure from the paper as the primary anchor, not only free-text similarity.

## Output Requirements

For each question, provide:

- 题号
- 得分
- 结论：正确 / 基本正确 / 部分正确 / 错误 / 未作答
- 参考答案关键点
- 我的答案问题
- 改进建议

Also provide a full summary including:

- 总分
- 各题型表现
- 掌握较好的知识点
- 薄弱知识点
- 容易混淆的概念
- 下一轮复习建议

If a temporary scoring scheme had to be created because the paper lacked one, include a short `评分说明` section near the top of the report.

## Report File

In addition to replying in chat, save the grading report into the same batch directory.

Use this filename rule:

- `04_<base_name>_批改报告.md`

Here `<base_name>` should match the common paper prefix used in the batch, for example:

- `04_Java基础面试题_批改报告.md`

If the report file already exists, overwrite it with the newest grading result unless the user explicitly asks to keep old versions.

## Execution Requirements

Actually read the 3 batch files and actually write the grading report file.

Do not only describe how grading should be done.

After writing the report, briefly verify that it exists.

Also verify that:

- the report's question count matches the paper's graded question count
- the section subtotals add up correctly
- the final total matches the section totals

## Final Response

In the final reply:

- give a short grading summary
- provide the absolute path to the grading report file
- mention the batch directory that was graded
- mention whether the report used the original paper scoring scheme or a clearly stated temporary scoring scheme
