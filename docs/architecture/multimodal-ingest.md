# Ingest: text-first, multimodal where the text runs out (proposal, 2026-11-19)

**Scope**: should parsing be multimodal (page images → vision model) or text-only? Grounded in our own corpus; proposes ADR-016 and the experiment that decides it.

## 1. Evidence from our corpus

| Material | Median characters / page | Pages under 150 characters |
|---|---|---|
| *Attention*, *MapReduce*, *ResNet* (papers) | 3 100 – 5 100 | **0 %** |
| CS231n lectures 1–2 (slides) | ≈ 220 | **21 – 22 %** |

Papers lose almost nothing with text extraction. **Slides lose about a fifth of their pages** — diagrams, charts, timelines with a title and nothing else. Slides are what students mostly have (product-review §2, P5), and the eval already showed slide coverage at 5/8 key ideas. Scanned PDFs (`NO_TEXT_LAYER`) lose everything.

## 2. Decision (proposed ADR-016): text-first, vision only where the text runs out

| Page kind | Detected by | Path | Trust |
|---|---|---|---|
| Text-rich page | ≥ 150 extracted characters | text extraction as today | quotes verified verbatim (substring) |
| **Low-text page** (diagram slide, chart, figure) | < 150 characters, or image objects cover most of the page (PDFBox) | render the page (PDFBox, ~150 dpi) → **vision call on the server** → a `figure` chunk: labels/numbers read off the page + a description of the diagram | **lower**: cannot be verified verbatim → see rules below |
| Scanned PDF / photo | no text layer | photos: **on-device ML Kit OCR** (image stays on the phone); scanned PDFs: the same server vision path on every page | as OCR text |

Why not "everything multimodal":
- **Cost and latency**: a vision call per page multiplies cost for papers that gain nothing.
- **Hallucination**: a vision description is generated text, not the document's words; our grounding checks (quote ⊂ chunk) cannot verify it. Precision over recall says: use vision only where it adds content that text cannot.

## 3. Rules for figure-derived content

1. Figure chunks carry `origin: vision` and a page-image reference.
2. A unit whose answer rests **only** on vision chunks must pass the support gate **with the page image** attached (multimodal gate), and is labelled *from a figure* in the app.
3. Evidence for such a unit shows **the page image itself**, not a highlighted quote — the player can see the figure and judge. (The app can render the page locally with Android `PdfRenderer` when the PDF came from the phone; otherwise the server serves the rendered page.)
4. Numbers read from a figure may become answer keys only if the gate confirms them against the image; otherwise the unit is dropped.
5. Never call a vision model from the phone (ADR-013): the server renders and calls; photos are OCR'd on the device.

## 4. Contract (additive, v1-compatible)

`SourceChunk` gains optional `origin` (`text` default | `vision` | `ocr`) and optional `pageImage` (a server URL or `local:` reference). Clients that ignore them still work.

## 5. Experiment that decides it

Corpus: the CS231n decks + 2–3 more slide decks from other courses + one scanned PDF. Arms: A+G (text only) vs A+G + figure chunks.

| Outcome | Meaning | Decision |
|---|---|---|
| slide coverage of key ideas rises (5/8 → ≥ 7/8) and wrong-answer parts on figure-derived units ≤ 2× text-derived | vision adds content without adding many errors | adopt for low-text pages |
| coverage rises but figure units are often wrong | vision content not trustworthy enough | keep figures as context only (no answer keys from them) |
| no coverage gain | slides' figures carry little testable content | stay text-only |

Cost guard: vision only on low-text pages (≈ 20 % of slide pages, ≈ 0 % of paper pages).
