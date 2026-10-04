"""Generates the docs-agent test documents (python-docx). Run from docs/testdata: python make_testdata.py"""
import struct
import zlib

from docx import Document
from docx.enum.text import WD_ALIGN_PARAGRAPH, WD_COLOR_INDEX
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Inches, RGBColor


def png(w, h):
    raw = b''.join(b'\x00' + b''.join(bytes([x * 255 // w, y * 255 // h, 180]) for x in range(w)) for y in range(h))

    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)

    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw)) + chunk(b'IEND', b''))


def add_link(par, url, text):
    r_id = par.part.relate_to(url, "http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink", is_external=True)
    h = OxmlElement('w:hyperlink'); h.set(qn('r:id'), r_id)
    r = OxmlElement('w:r'); rpr = OxmlElement('w:rPr')
    s = OxmlElement('w:rStyle'); s.set(qn('w:val'), 'Hyperlink'); rpr.append(s)
    r.append(rpr); t = OxmlElement('w:t'); t.text = text; r.append(t); h.append(r); par._p.append(h)


def english():
    with open('circuit.png', 'wb') as f:
        f.write(png(320, 180))
    d = Document()
    d.add_heading("Lab 3: Ohm's Law and Series Circuits", 0)
    p = d.add_paragraph('Course: '); p.add_run('PHYS 102').bold = True; p.add_run(' · Due: '); p.add_run('Week 5').italic = True
    d.add_heading('1. Objectives', 1)
    d.add_paragraph("Verify Ohm's law V = IR for a fixed resistor.", style='List Bullet')
    d.add_paragraph('Measure equivalent resistance of resistors in series.', style='List Bullet')
    d.add_paragraph('Digital multimeter in DC mode', style='List Bullet 2')
    d.add_paragraph('Breadboard and jumper wires', style='List Bullet 2')
    d.add_heading('2. Procedure', 1)
    for s in ['Connect the circuit as shown in Figure 1.', 'Set the supply to 2 V and record the current.',
              'Increase the voltage in 1 V steps up to 10 V.', 'Repeat with R2 in series.']:
        d.add_paragraph(s, style='List Number')
    d.add_paragraph('Figure 1 — the series circuit:')
    d.add_picture('circuit.png', width=Inches(4))
    d.paragraphs[-1].alignment = WD_ALIGN_PARAGRAPH.CENTER
    d.add_heading('3. Results', 1)
    t = d.add_table(rows=1, cols=3); t.style = 'Table Grid'
    hdr = t.rows[0].cells; hdr[0].text = 'Voltage (V)'; hdr[1].text = 'Current (mA)'; hdr[2].text = 'R = V/I (Ω)'
    for v, i in [(2, 0.98), (4, 1.97), (6, 2.95), (8, 3.99), (10, 5.02)]:
        c = t.add_row().cells; c[0].text = str(v); c[1].text = f'{i:.2f}'; c[2].text = f'{v / i * 1000:.0f}'
    row = t.add_row().cells; m = row[0].merge(row[2]); m.text = 'Mean resistance ≈ 2010 Ω'
    d.add_heading('3.1 Error analysis', 2)
    p = d.add_paragraph('Percentage error E = |R'); p.add_run('meas').font.subscript = True
    p.add_run(' − R'); p.add_run('nom').font.subscript = True; p.add_run('| / R × 100. Area is in m'); p.add_run('2').font.superscript = True
    p = d.add_paragraph(); r = p.add_run('Important: '); r.bold = True; r.font.color.rgb = RGBColor(0xC0, 0x00, 0x00)
    p.add_run('do not exceed 0.25 W on the resistor. ').font.highlight_color = WD_COLOR_INDEX.YELLOW
    p.add_run('Old value').font.strike = True
    d.add_heading('4. References', 1)
    p = d.add_paragraph('Lab manual online: '); add_link(p, 'https://example.com/phys102/lab3', 'example.com/phys102/lab3')
    d.add_page_break()
    d.add_heading('Appendix', 1)
    d.add_paragraph('def ohm(v, i):\n    return v / i').runs[0].font.name = 'Courier New'
    d.save('lab_sheet_en.docx')


def rtl(par):
    ppr = par._p.get_or_add_pPr(); ppr.insert(0, OxmlElement('w:bidi'))
    for r in par.runs:
        r._r.get_or_add_rPr().append(OxmlElement('w:rtl'))
    return par


def arabic():
    a = Document()
    rtl(a.add_heading('تقرير المختبر: قانون أوم', 0))
    rtl(a.add_heading('الأهداف', 1))
    rtl(a.add_paragraph('التحقق من قانون أوم باستخدام مقاومة ثابتة وجهاز Multimeter رقمي بجهد 5 V.'))
    rtl(a.add_paragraph('قياس التيار عند جهود مختلفة.', style='List Number'))
    rtl(a.add_paragraph('حساب المقاومة المكافئة (R = V / I).', style='List Number'))
    rtl(a.add_paragraph('ملاحظة مهمة', style='List Bullet'))
    rtl(a.add_heading('النتائج', 1))
    t = a.add_table(rows=2, cols=2); t.style = 'Table Grid'
    t._tbl.tblPr.append(OxmlElement('w:bidiVisual'))
    t.cell(0, 0).text = 'الجهد (V)'; t.cell(0, 1).text = 'التيار (mA)'; t.cell(1, 0).text = '٥'; t.cell(1, 1).text = '2.5'
    for c in [t.cell(0, 0), t.cell(0, 1), t.cell(1, 0), t.cell(1, 1)]:
        rtl(c.paragraphs[0])
    rtl(a.add_paragraph('فقرة مختلطة: The English phrase "Ohm\'s law" داخل جملة عربية، والرقم 220 فولت.'))
    a.add_paragraph('An English paragraph inside the Arabic document stays left-to-right.')
    p = a.add_paragraph('محاذاة في الوسط'); p.alignment = WD_ALIGN_PARAGRAPH.CENTER; rtl(p)
    a.add_paragraph('فقرة عربية بدون علامة bidi تُعرض من اليمين إلى اليسار تلقائيًا.')
    a.save('arabic_doc.docx')


def big():
    d = Document()
    for ch in range(1, 41):
        d.add_heading(f'Chapter {ch}', 1)
        for k in range(1, 4):
            d.add_heading(f'Section {ch}.{k}', 2)
            for _ in range(6):
                d.add_paragraph('Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt '
                                'ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation. ' * 2)
            d.add_paragraph('Step one', style='List Number'); d.add_paragraph('Step two', style='List Number')
        d.add_page_break()
    d.save('large_120_pages.docx')


if __name__ == '__main__':
    english(); arabic(); big()
    with open('corrupt.docx', 'wb') as f:
        f.write(b'PK\x03\x04 this is not really a zip file at all')
    with open('legacy.doc', 'wb') as f:
        f.write(bytes([0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1]) + b'\x00' * 504)
    print('ok')
