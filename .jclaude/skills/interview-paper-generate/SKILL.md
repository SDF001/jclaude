---
description: Generate an interview paper batch from a source Markdown question bank, including paper, answer sheet, and a ready-to-fill candidate response file.
when_to_use: Use when the user wants to generate a reusable interview paper batch from a specific interview-topic Markdown file and wants the outputs saved into a new timestamped batch directory.
argument-hint: "\"/absolute/path/to/interview_questions_optimized.md\""
arguments: paper_path
---
# Interview Paper Generate

Treat `$paper_path` as the absolute path to the source interview-topic Markdown file.

If `$paper_path` is empty, not an absolute path, or does not point to a readable Markdown file, stop and ask the user only for the correct absolute file path.

If the path contains spaces, treat the quoted path as the real argument value.

## Goal

Generate a new interview-paper batch from the source Markdown file and save all outputs into a newly created batch directory located beside the source file.

Do not ask the user to manually edit any template placeholders. Execute the workflow directly.

## Required Outputs

Create exactly one new batch directory in the same directory as the source Markdown file.

Use this directory naming rule:

- `<source_filename_without__optimized>_试卷批次_YYYYMMDD_HHMMSS`

If a directory with the same name already exists, append `_01`, `_02`, `_03` and so on.

Inside that batch directory, create exactly these 3 files in this order:

1. `01_<source_filename_without__optimized>_试卷.md`
2. `02_<source_filename_without__optimized>_我的作答.md`
3. `03_<source_filename_without__optimized>_试卷答案.md`

## Source Constraints

Base every question strictly on the source Markdown file.

Do not go beyond the source material.

Cover all core knowledge points, major concepts, high-frequency interview topics, and common pitfalls from the source file.

Do not guess image content from filenames or surrounding text when the image itself has not actually been provided as visual input.

## Image Path Rules

Preserve the original image files themselves. Do not rename, move, or delete the source image assets.

However, do not blindly keep the original Markdown image path text unchanged when the output Markdown files are written into a deeper batch directory.

For every image link found in the source Markdown:

- if it is an absolute URL such as `http://`, `https://`, or a `data:` URL, keep it unchanged
- if it is an absolute local filesystem path, keep it unchanged
- if it is a relative local path, resolve it relative to the source Markdown file's directory first, then write the image link in each generated file as a relative path that is valid from the generated file's own location

In this workflow, the generated files are placed inside a child batch directory under the source file directory, so a source image path like:

- `Java集合面试题_optimized_images/file0.webp`

will usually need to become:

- `../Java集合面试题_optimized_images/file0.webp`

in `01_..._试卷.md`, `02_..._我的作答.md`, and `03_..._试卷答案.md`.

The rule is: generated Markdown files must contain image links that actually work from the batch directory, not merely links that matched the source file text.

## Image Decision Rules

Before deciding whether to keep or drop an image in the generated paper set, determine one practical fact from the current request context:

- whether you can actually inspect the image pixels in this request, rather than only seeing a Markdown path, filename, alt text, or surrounding source text

Use this policy strictly:

- if you cannot directly inspect the image content in this request, treat the image as unseen
- for unseen images, do not guess image content and do not create image-based questions
- only when the image itself has actually been provided as visual input that you can inspect may you decide how to use it

When image inspection is allowed, classify each image conservatively:

- keep an image in `02_..._我的作答.md` only if it is strictly necessary for understanding the question stem itself
- if you are not sure whether an image is necessary or whether it leaks the answer, do not keep it in `02_..._我的作答.md`
- obvious answer-bearing images must never appear in `02_..._我的作答.md`

Treat the following as answer-bearing by default unless there is a very strong reason not to:

- 定义表
- 解释表
- 对照表
- 参数说明表
- 状态说明图
- 带结论标签的流程图
- 总结图
- 原文截图中直接写出结论、定义、步骤、区别或答案要点的图片

These answer-bearing images may appear in `03_..._试卷答案.md` when helpful.

## Paper Rules

Generate a well-structured interview paper that mixes suitable question types such as:

- 单选题
- 多选题
- 判断题
- 简答题
- 场景分析题

When the source material clearly supports them, you may also include:

- 代码理解题
- 综合分析题
- 对比辨析题

Do not force every topic to contain 场景题、代码题、综合题. These question types are optional and should appear only when the source material naturally supports them.

Balance the paper across:

- 基础题
- 理解题
- 进阶题

Keep the paper readable and interview-oriented. Avoid near-duplicate questions.

If an image-dependent knowledge point cannot be asked safely under the image rules above, convert it into a pure-text question only when the source text itself is sufficient; otherwise skip that image-based question.

Prefer a finished mock-paper feel over a loose question dump.

Choose the paper size according to the source material's actual breadth and density.

Use these sizing heuristics:

- thin source material: roughly 12 to 20 questions
- medium source material: roughly 20 to 35 questions
- dense or long source material: roughly 35 to 45 questions

When source coverage and paper quality conflict with the target range, preserve coverage quality and avoid padding with weak or repetitive questions.

Do not mechanically split every tiny knowledge point into a separate short question just to maximize coverage. It is better to merge closely related points into one higher-quality interview question when that produces a more natural paper.

At the top of `01_..._试卷.md`, always include:

- 试卷名称
- 题目来源
- 试卷结构概览
- 总分
- 建议用时

Also give each major section a clear score design when the paper structure reasonably supports scoring. Prefer an exam-like structure such as:

- 某一题型共 N 题
- 每题 X 分
- 本部分共 Y 分

If the source material is not suitable for strict scoring, still provide a reasonable total score and per-section scoring guidance rather than omitting scores entirely.

The paper should read like a reusable mock interview paper, not merely a reordered outline of source headings.

## Candidate Response File Rules

Create `02_..._我的作答.md` as the primary file the user will edit.

This file must:

- keep the same chapter structure, question order, and question text as the paper
- provide a clear answer area under every question
- never reveal standard answers, scoring rubrics, or explanations
- never include answer-bearing images
- only include images that are strictly necessary for understanding the question, and only when the image rules above allow it

Design it so the user can finish the whole paper by editing only this one file.

At the top of the file, add a short note telling the user to fill answers directly in this file and not change question numbering.

Keep the answer areas clean and spacious enough for real writing. The candidate response file should preserve the formal paper structure and scoring labels from the paper where helpful, but must not expose answer cues.

In `02_..._我的作答.md`, apply scoring presentation like this:

- if `01_..._试卷.md` defines per-question point values, keep those per-question point values in `02_..._我的作答.md` by default
- you may keep section-level scoring information such as `本部分共 Y 分` when it helps preserve the paper structure
- you may keep per-section labels such as `共 N 题`
- when the paper already states `每题 X 分`, prefer preserving that wording in the corresponding section heading
- do not invent per-question point values that were not defined in the paper
- do not include `采分点`
- do not include scoring rubrics
- do not include explanatory score breakdowns
- do not include answer-analysis hints

## Answer File Rules

Create `03_..._试卷答案.md` with one-to-one correspondence to the paper.

For each question, include:

- 标准答案或参考答案
- 知识点
- 解析
- 采分点（对主观题）
- 易错点或面试官追问点（适合时）

Keep answers concise, accurate, and suitable for interview review.

You may keep relevant answer-bearing images here when they help explain the answer.

Prefer a teaching-style answer sheet over an answer-only key.

For客观题, keep the answer direct but still include a short explanation when it adds value.

For主观题, do not reply with only one short sentence. Provide a structured reference answer with enough explanation to support review and self-study.

When the source material supports it, the answer file may include:

- 分步骤解析
- 对比说明
- 关键代码片段
- 追问方向

The answer file should feel like a high-quality review companion to the paper, not just a minimal answer list.

## Execution Requirements

Actually create the new directory and the 3 Markdown files on disk.

Do not only describe what should be created.

After writing files, briefly verify that the directory and files exist.

Also verify local image links:

- for each generated Markdown file, check that every local relative image path resolves to an existing file from that generated file's location
- if any generated image link would be broken, rewrite it before finishing
- do not treat a path as valid only because it "looks right"; it must resolve to a real readable file on disk
- treat this as a required post-generation validation step, not an optional best-effort check
- only finish after all local image links in the generated files have been validated as accessible, or after explicitly reporting which specific links could not be made valid

Also verify content safety for `02_..._我的作答.md`:

- re-scan every kept image in that file
- if an image is not clearly required for understanding the question, remove it from `02_..._我的作答.md`
- if an image appears to contain definitions, explanations, conclusions, tables, state descriptions, or direct answer cues, remove it from `02_..._我的作答.md` and keep it only in `03_..._试卷答案.md`

## Image Validation Checklist

Before finishing, perform this exact validation mindset for every generated Markdown file:

1. Extract every Markdown image link.
2. Ignore remote URLs such as `http://`, `https://`, and `data:` URLs.
3. For every local path, determine whether it is absolute or relative.
4. For every relative local path, resolve it from the generated file's own directory, not from the source Markdown file's directory.
5. Confirm the resolved target exists on disk and is readable.
6. If a link is broken, rewrite the Markdown path and validate again.
7. Do not stop until the generated file's local image links are all valid, or until you explicitly report the remaining invalid links to the user.

## Final Response

In the final reply:

- state that the batch directory was created
- provide the absolute path of the batch directory
- list the 3 generated files
- explicitly tell the user to edit `02_..._我的作答.md` for answering
- mention that local image links were validated after generation
- explicitly mention whether `02_..._我的作答.md` retained any images, and that they were filtered under the image-decision rules above
- tell the user they can later use `/interview-paper-grade "<batch_dir>"` to grade that batch
