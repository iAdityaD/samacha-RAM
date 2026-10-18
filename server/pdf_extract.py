"""Isolated bounded PDF parser. Invoked as a subprocess; never renders PDF scripts."""
import io
import json
import sys

def extract(data):
    from pypdf import PdfReader
    reader=PdfReader(io.BytesIO(data))
    if reader.is_encrypted: raise ValueError('Encrypted PDFs are unsupported.')
    if len(reader.pages)>400: raise ValueError('PDF exceeds the 400-page limit.')
    pages=[]; size=0
    for i,page in enumerate(reader.pages):
        text=page.extract_text() or ''
        size+=len(text)
        if size>2_000_000: raise ValueError('Extracted PDF exceeds the text limit.')
        pages.append({'page':i+1,'text':text})
    if not any(x['text'].strip() for x in pages): raise ValueError('No embedded text. OCR is required and is not inferred.')
    return pages

if __name__=='__main__':
    try:
        try:
            import resource
            resource.setrlimit(resource.RLIMIT_AS,(768*1024**2,768*1024**2))
            resource.setrlimit(resource.RLIMIT_CPU,(15,15))
        except ImportError: pass
        print(json.dumps(extract(sys.stdin.buffer.read(25*1024**2+1))))
    except Exception:
        print('PDF parsing failed: check page count, encryption, embedded text, and installed pypdf.',file=sys.stderr)
        sys.exit(1)
