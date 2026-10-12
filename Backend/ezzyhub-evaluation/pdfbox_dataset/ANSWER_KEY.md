# PDFBox real-world test — ground-truth answer key

Project: Apache PDFBox core module (commit 833ed8f), 618 Java files.
Ground truth established by locating (or confirming the absence of) the implementing class in the source.

| Req | Expected | Implementing class (oracle) | Note |
|---|---|---|---|
| REQ01 | **IMPLEMENTED** | PDDocument.java | PDDocument.load / parser |
| REQ02 | **IMPLEMENTED** | PDPageContentStream.java | content stream writing |
| REQ03 | **IMPLEMENTED** | PDFTextStripper.java |  |
| REQ04 | **IMPLEMENTED** | PDFRenderer.java |  |
| REQ05 | **IMPLEMENTED** | StandardProtectionPolicy.java |  |
| REQ06 | **IMPLEMENTED** | StandardSecurityHandler.java |  |
| REQ07 | **IMPLEMENTED** | PDAcroForm.java, PDField.java |  |
| REQ08 | **IMPLEMENTED** | PDFMergerUtility.java |  |
| REQ09 | **IMPLEMENTED** | Splitter.java |  |
| REQ10 | **IMPLEMENTED** | PDTrueTypeFont.java, PDType0Font.java |  |
| REQ11 | **IMPLEMENTED** | Overlay.java |  |
| REQ12 | **IMPLEMENTED** | PDSignature.java |  |
| REQ13 | **IMPLEMENTED** | PDImageXObject.java |  |
| REQ14 | **IMPLEMENTED** | PDAnnotationLink.java |  |
| REQ15 | **MISSING** | — (none) | no OCR engine in PDFBox |
| REQ16 | **MISSING** | — (none) | no docx export |
| REQ17 | **MISSING** | — (none) | no HTML rendering |
| REQ18 | **MISSING** | — (none) | no translation |
| REQ19 | **MISSING** | — (none) | PDFBox has no supported find-and-replace text editing (known limitation); manual-judgment edge case |

## Requirement texts

- **REQ01** (IMPLEMENTED): The system shall load and parse an existing PDF file from disk into an in-memory document object that exposes its pages and metadata.
- **REQ02** (IMPLEMENTED): The system shall create a brand-new PDF document and write text and vector graphics onto its pages.
- **REQ03** (IMPLEMENTED): The system shall extract the plain-text content of a PDF in natural reading order.
- **REQ04** (IMPLEMENTED): The system shall render a page of a PDF to a raster image (such as PNG) at a caller-specified resolution in DPI.
- **REQ05** (IMPLEMENTED): The system shall encrypt a PDF using user and owner passwords and enforce permission flags such as disallowing printing.
- **REQ06** (IMPLEMENTED): The system shall open and decrypt a password-protected PDF when the correct password is supplied.
- **REQ07** (IMPLEMENTED): The system shall read and populate the values of interactive form fields in an AcroForm PDF.
- **REQ08** (IMPLEMENTED): The system shall merge several separate PDF documents into a single combined PDF.
- **REQ09** (IMPLEMENTED): The system shall split a PDF document into multiple smaller documents by page ranges.
- **REQ10** (IMPLEMENTED): The system shall embed a custom TrueType or OpenType font into a PDF so text displays in that font.
- **REQ11** (IMPLEMENTED): The system shall stamp one PDF as a watermark or overlay on top of the pages of another PDF.
- **REQ12** (IMPLEMENTED): The system shall apply a digital signature to a PDF document.
- **REQ13** (IMPLEMENTED): The system shall extract embedded raster images contained in a PDF's pages.
- **REQ14** (IMPLEMENTED): The system shall add a clickable hyperlink annotation to a region of a PDF page.
- **REQ15** (MISSING): The system shall perform optical character recognition on a scanned, image-only PDF to recover selectable, searchable text.
- **REQ16** (MISSING): The system shall export/convert a PDF into an editable Microsoft Word (.docx) document.
- **REQ17** (MISSING): The system shall convert an HTML web page directly into a PDF document.
- **REQ18** (MISSING): The system shall automatically translate all text in a PDF from one natural language into another.
- **REQ19** (MISSING): The system shall find a specific text string anywhere in an existing PDF and replace it in place, then save the edited file.
