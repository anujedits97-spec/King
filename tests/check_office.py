"""Builds the office files with brain/office.mjs and opens them with the real Python libraries.
Run from this folder: python3 check_office.py   (needs node, python-docx, openpyxl, python-pptx, pypdf)"""
import subprocess, warnings, sys
warnings.simplefilter('error')
subprocess.run(['node', 'office_gen.mjs'], check=True)
import docx, openpyxl, pptx
from pypdf import PdfReader
d = docx.Document('out.docx'); assert any(p.text.startswith('Quarterly') for p in d.paragraphs)
wb = openpyxl.load_workbook('out.xlsx'); assert wb['Sales Q3']['D2'].value == '=B2*C2'
p = pptx.Presentation('out.pptx'); assert len(p.slides) == 3
r = PdfReader('out.pdf'); assert 'Quarterly review' in r.pages[0].extract_text()
print('office files OK: docx, xlsx, pptx, pdf')
