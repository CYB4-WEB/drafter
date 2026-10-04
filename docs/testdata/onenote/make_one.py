#!/usr/bin/env python3
"""Generates small but structurally valid OneNote 2010+ section files ([MS-ONESTORE] + [MS-ONE]) and .onepkg packages.

Outputs (next to this script):
  sample.one           2 pages + 1 sub-page: title, rich text runs (bold/italic/underline/colour/size/highlight),
                       hyperlink field code, bullets, numbered list (nested), table with borders, PNG image,
                       ink strokes (InkContainer/InkDataNode/InkStrokeNode/StrokePropertiesNode), attached file,
                       Arabic RTL page.
  lab.one              second section (used inside the packages)
  sample.onepkg        CAB, one MSZIP folder: Lectures.one, Labs/Lab 1.one, Open Notebook.onetoc2 (skipped by the reader),
                       OneNote_RecycleBin/Deleted.one (skipped)
  sample_stored.onepkg CAB, uncompressed folder
  encrypted.one        section carrying an ObjectDataEncryptionKeyV2FNDX node (reader must report "password protected")
  toc.onetoc2          table-of-contents header (reader must report TOC)
  corrupt.one          sample.one truncated to 3000 bytes (reader must fail cleanly)

Everything is written from the public specs; node/field layout comments name the spec structures.
"""
import os
import struct
import uuid
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))

# ----------------------------------------------------------------------------------------------- primitives

def guid_bytes(s):
    return uuid.UUID(s).bytes_le

def xg(g, n):
    """ExtendedGUID: GUID + u32 n."""
    return guid_bytes(g) + struct.pack('<I', n)

NIL_XG = b'\0' * 20
TYPE_ONE = '7B5C52E4-D88C-4DA7-AEB1-5378D02996D3'
TYPE_TOC = '43FF2FA1-EFD9-4C76-9EE2-10EA5722765F'
FORMAT = '109ADD3F-911B-49F5-A5D0-1791EDC8AED8'
FRAG_MAGIC = 0xA4567AB1F5F7F4C4
FRAG_FOOTER = 0x8BC215C38233BA4B
FDS_HEADER = 'BDE316E7-2665-4511-A4C4-8D4D0B7A9EAC'
FDS_FOOTER = '71FBA722-0F79-4A0B-BB13-899256426B24'

def g(n):
    """Deterministic GUIDs so outputs are reproducible."""
    return str(uuid.UUID(int=(0x6461667461720000 << 64) | n)).upper()


class Writer:
    """Appends chunks after the 1024-byte header; returns (stp, cb) references."""

    def __init__(self):
        self.buf = bytearray(1024)
        self.list_counts = {}   # FileNodeListID -> node count (for the transaction log)
        self.next_list = 0x10

    def chunk(self, data, align=8):
        while len(self.buf) % align:
            self.buf.append(0)
        stp = len(self.buf)
        self.buf += data
        return (stp, len(data))

    def node(self, fid, body=b'', ref=None, base=0):
        """FileNode: header (FileNodeID 10, Size 13, StpFormat 2, CbFormat 2, BaseType 4, Reserved 1) + data.
        References use StpFormat 0 (8 bytes) and CbFormat 0 (4 bytes)."""
        data = b''
        if ref is not None:
            data += struct.pack('<QI', ref[0], ref[1])
            base = base or 1
        data += body
        size = 4 + len(data)
        h = fid | (size << 10) | (0 << 23) | (0 << 25) | (base << 27) | (1 << 31)
        return struct.pack('<I', h) + data

    def file_node_list(self, nodes):
        """FileNodeListFragment: header (magic, FileNodeListID, nFragmentSequence) + nodes + nextFragment (nil) + footer."""
        lid = self.next_list
        self.next_list += 1
        body = b''.join(nodes)
        frag = struct.pack('<QII', FRAG_MAGIC, lid, 0) + body
        frag += b'\0' * ((-len(frag)) % 8)
        frag += struct.pack('<QI', 0xFFFFFFFFFFFFFFFF, 0)   # nextFragment = fcrNil
        frag += struct.pack('<Q', FRAG_FOOTER)
        self.list_counts[lid] = len(nodes)
        return self.chunk(frag)


# ----------------------------------------------------------------------------------------------- property sets

def pid(prid, value=None):
    return (prid, value)

class OidRef:
    def __init__(self, compacts): self.compacts = compacts

class OsidRef:
    def __init__(self, compacts): self.compacts = compacts

class PropArr:
    def __init__(self, sets): self.sets = sets


def prop_set(props, oids, osids):
    """PropertySet: cProperties u16, rgPrids, rgData; object/space references go to the OID / OSID streams."""
    prids = b''
    data = b''
    for prid, v in props:
        t = (prid >> 26) & 0x1F
        if t == 0x2:
            prid = (prid & 0x7FFFFFFF) | (0x80000000 if v else 0)
        elif t in (0x3, 0x4, 0x5, 0x6):
            data += v
        elif t == 0x7:
            data += struct.pack('<I', len(v)) + v
        elif t == 0x8:
            oids.extend(v.compacts[:1])
        elif t == 0x9:
            data += struct.pack('<I', len(v.compacts)); oids.extend(v.compacts)
        elif t == 0xA:
            osids.extend(v.compacts[:1])
        elif t == 0xB:
            data += struct.pack('<I', len(v.compacts)); osids.extend(v.compacts)
        elif t == 0x10:
            data += struct.pack('<I', len(v.sets))
            if v.sets:
                data += struct.pack('<I', 0x44000000)  # element prid: type 0x11 (PropertySet)
                for s in v.sets:
                    data += prop_set(s, oids, osids)
        else:
            raise ValueError(hex(prid))
        prids += struct.pack('<I', prid)
    return struct.pack('<H', len(props)) + prids + data


def object_prop_set(props):
    """ObjectSpaceObjectPropSet: OIDs stream, OSIDs stream (when used), body, padding to 8."""
    oids, osids = [], []
    body = prop_set(props, oids, osids)
    out = struct.pack('<I', len(oids) | ((0 if osids else 1) << 31)) + b''.join(struct.pack('<I', c) for c in oids)
    if osids:
        out += struct.pack('<I', len(osids)) + b''.join(struct.pack('<I', c) for c in osids)
    out += body
    out += b'\0' * ((-len(out)) % 8)
    return out

# value helpers
u8 = lambda v: struct.pack('<B', v)
u16 = lambda v: struct.pack('<H', v)
u32 = lambda v: struct.pack('<I', v)
f32 = lambda v: struct.pack('<f', v)
utf16 = lambda s: s.encode('utf-16-le')

def colorref(rgb):
    r, gg, b = (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF
    return u32(r | (gg << 8) | (b << 16))

def isf_signed(values):
    """[MS-ISF] multi-byte encoding: count (as signed value), then signed values (sign in bit 0)."""
    def enc_u(v):
        out = bytearray()
        while True:
            b = v & 0x7F
            v >>= 7
            if v:
                out.append(b | 0x80)
            else:
                out.append(b)
                return bytes(out)
    def enc_s(v):
        return enc_u((abs(v) << 1) | (1 if v < 0 else 0))
    return enc_s(len(values)) + b''.join(enc_s(v) for v in values)

# property ids ([MS-ONE])
PageWidth, PageHeight = 0x14001C01, 0x14001C02
Bold, Italic, Underline, Strike = 0x08001C04, 0x08001C05, 0x08001C06, 0x08001C07
Font, FontSize, FontColor, Highlight = 0x1C001C0A, 0x10001C0B, 0x14001C0C, 0x14001C0D
OffH, OffV = 0x14001C14, 0x14001C15
NumberListFormat, LayoutMaxWidth = 0x1C001C1A, 0x14001C1B
ContentChildNodes, ElementChildNodes = 0x24001C1F, 0x24001C20
RichEditTextUnicode, ListNodes, OutlineElementRTL = 0x1C001C22, 0x24001C26, 0x08001C34
PictureContainer, ListFont, ListRestart = 0x20001C3F, 0x1C001C52, 0x14001CB7
CachedTitleString, CreationTimeStamp = 0x1C001CF3, 0x14001D09
RowCount, ColumnCount, TableBordersVisible = 0x14001D57, 0x14001D58, 0x08001D5E
StructureElementChildNodes, ChildGraphSpaceElementNodes, TableColumnWidths = 0x24001D5F, 0x2C001D63, 0x1C001D66
LastModifiedTime = 0x14001D7A
EmbeddedFileContainer, EmbeddedFileName = 0x20001D9B, 0x1C001D9C
PageLevel, TextRunIndex, TextRunFormatting, Hyperlink = 0x14001DFF, 0x1C001E12, 0x24001E13, 0x08001E14
ImageAltText, ParagraphStyle, MetaDataObjectsAboveGraphSpace = 0x1C001E58, 0x2000342C, 0x24003442
ParagraphStyleId, ParagraphAlignment = 0x1C00345A, 0x0C003477
PictureWidth, PictureHeight = 0x140034CD, 0x140034CE
InkStrokeProperties, InkDimensions, InkPath = 0x20003409, 0x1C00340A, 0x1C00340B
InkHeight, InkWidth, InkColor, InkTransparency = 0x1400340C, 0x1400340D, 0x1400340F, 0x0C003414
InkData, InkStrokes, InkScalingX, InkScalingY = 0x20003415, 0x24003416, 0x14001C46, 0x14001C47
InkBoundingBox = 0x1C003418

# JCIDs
jcidSectionNode, jcidPageSeriesNode, jcidPageNode = 0x00060007, 0x00060008, 0x0006000B
jcidOutlineNode, jcidOutlineElementNode, jcidRichTextOENode = 0x0006000C, 0x0006000D, 0x0006000E
jcidImageNode, jcidNumberListNode, jcidInkContainer = 0x00060011, 0x00060012, 0x00060014
jcidTableNode, jcidTableRowNode, jcidTableCellNode, jcidTitleNode = 0x00060022, 0x00060023, 0x00060024, 0x0006002C
jcidPageMetaData, jcidEmbeddedFileNode, jcidPageManifestNode = 0x00020030, 0x00060035, 0x00060037
jcidEmbeddedFileContainer, jcidPictureContainer14 = 0x00080036, 0x00080039
jcidInkDataNode, jcidInkStrokeNode, jcidStrokePropertiesNode = 0x0002003B, 0x00020047, 0x00120048
jcidParagraphStyleObject = 0x0012004D

X_DIM = '598A6A8F-52C0-4BA0-93AF-AF357411A561'
Y_DIM = 'B53F9F75-04E0-4498-A7EE-C30DBB5A9011'

TIME32_2026 = 1459468800  # seconds since 1980-01-01 → 2026-04-01 UTC

# ----------------------------------------------------------------------------------------------- object space builder

class Space:
    """Collects objects of one object space (one object group, one revision)."""

    def __init__(self, gosid_guid, seed):
        self.gosid = gosid_guid
        self.oid_guid = g(seed)
        self.table = [self.oid_guid]   # global id table: index → GUID
        self.objects = []              # (compact, jcid, props) or (compact, jcid, fileref)
        self.n = 0

    def index_of(self, guid):
        if guid not in self.table:
            self.table.append(guid)
        return self.table.index(guid)

    def new(self, jcid, props):
        self.n += 1
        assert self.n < 256
        c = self.n | (0 << 8)
        self.objects.append((c, jcid, props))
        return c

    def file_object(self, jcid, blob_guid):
        self.n += 1
        c = self.n
        self.objects.append((c, jcid, '<ifndf>{%s}' % blob_guid))
        return c

    def osid(self, gosid_guid):
        return (0) | (self.index_of(gosid_guid) << 8)  # ExtendedGUID(gosid, 0)


def para(space, runs, style_id='p', align=None):
    """runs: list of (text, dict(format)). Returns RichTextOENode compact id."""
    text = ''.join(t for t, _ in runs)
    pstyle = space.new(jcidParagraphStyleObject, [pid(ParagraphStyleId, utf16(style_id)), pid(FontSize, u16(22)), pid(Font, utf16('Calibri'))])
    fmt_ids, idx, pos = [], [], 0
    for i, (t, f) in enumerate(runs):
        props = []
        if f.get('b'): props.append(pid(Bold, True))
        if f.get('i'): props.append(pid(Italic, True))
        if f.get('u'): props.append(pid(Underline, True))
        if f.get('s'): props.append(pid(Strike, True))
        if 'size' in f: props.append(pid(FontSize, u16(int(f['size'] * 2))))
        if 'color' in f: props.append(pid(FontColor, colorref(f['color'])))
        if 'hl' in f: props.append(pid(Highlight, colorref(f['hl'])))
        if f.get('link'): props.append(pid(Hyperlink, True))
        fmt_ids.append(space.new(jcidParagraphStyleObject, props))
        pos += len(t)
        if i < len(runs) - 1:
            idx.append(pos)
    props = [pid(RichEditTextUnicode, utf16(text)), pid(ParagraphStyle, OidRef([pstyle]))]
    if len(runs) > 1 or runs[0][1]:
        props += [pid(TextRunIndex, b''.join(u32(i) for i in idx)), pid(TextRunFormatting, OidRef(fmt_ids))]
    if align is not None:
        props.append(pid(ParagraphAlignment, u8(align)))
    return space.new(jcidRichTextOENode, props)


def oe(space, content, children=(), list_fmt=None, rtl=False, restart=None):
    props = [pid(ContentChildNodes, OidRef(list(content)))]
    if children:
        props.append(pid(ElementChildNodes, OidRef(list(children))))
    if list_fmt is not None:
        lp = [pid(NumberListFormat, utf16(list_fmt)), pid(ListFont, utf16('Calibri'))]
        if restart is not None:
            lp.append(pid(ListRestart, u32(restart)))
        props.append(pid(ListNodes, OidRef([space.new(jcidNumberListNode, lp)])))
    if rtl:
        props.append(pid(OutlineElementRTL, True))
    return space.new(jcidOutlineElementNode, props)


def outline(space, x_half_inch, y_half_inch, width_half_inch, elements):
    return space.new(jcidOutlineNode, [pid(OffH, f32(x_half_inch)), pid(OffV, f32(y_half_inch)),
                                        pid(LayoutMaxWidth, f32(width_half_inch)), pid(ElementChildNodes, OidRef(list(elements)))])


def make_png(w, h):
    rows = b''
    for y in range(h):
        row = b'\0'
        for x in range(w):
            row += bytes((40 + x * 3 % 200, 110 + y * 2 % 120, 220))
        rows += row
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xFFFFFFFF)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + \
        chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b'')


# ----------------------------------------------------------------------------------------------- whole file

def build_section(pages, seed, encrypted=False):
    """pages: list of builder functions (space, blobs) -> (page_compact, title, level)."""
    w = Writer()
    blobs = {}            # guid -> bytes
    root_gosid = g(seed)
    spaces = []           # (gosid, Space, roots dict role->compact)
    page_gosids, metas = [], []

    for i, build in enumerate(pages):
        gosid = g(seed + 100 + i)
        sp = Space(gosid, seed + 200 + i)
        page_c, title, level = build(sp, blobs)
        manifest = sp.new(jcidPageManifestNode, [pid(ContentChildNodes, OidRef([page_c]))])
        meta = sp.new(jcidPageMetaData, [pid(CachedTitleString, utf16(title)), pid(PageLevel, u32(level)),
                                          pid(CreationTimeStamp, u32(TIME32_2026 + i * 86400))])
        spaces.append((gosid, sp, {1: manifest, 2: meta}))
        page_gosids.append(gosid)
        metas.append((title, level, i))

    root = Space(root_gosid, seed + 1)
    meta_ids = [root.new(jcidPageMetaData, [pid(CachedTitleString, utf16(t)), pid(PageLevel, u32(lv)),
                                             pid(CreationTimeStamp, u32(TIME32_2026 + i * 86400))]) for t, lv, i in metas]
    series = root.new(jcidPageSeriesNode, [pid(ChildGraphSpaceElementNodes, OsidRef([root.osid(gs) for gs in page_gosids])),
                                           pid(MetaDataObjectsAboveGraphSpace, OidRef(meta_ids))])
    section = root.new(jcidSectionNode, [pid(ElementChildNodes, OidRef([series]))])
    spaces.insert(0, (root_gosid, root, {1: section}))

    # file data store
    fds_nodes = []
    for bg, data in blobs.items():
        obj = guid_bytes(FDS_HEADER) + struct.pack('<QIQ', len(data), 0, 0) + data
        obj += b'\0' * ((-len(obj)) % 8) + guid_bytes(FDS_FOOTER)
        ref = w.chunk(obj)
        fds_nodes.append(w.node(0x094, guid_bytes(bg), ref))
    fds_list = w.file_node_list(fds_nodes) if fds_nodes else None

    root_nodes = [w.node(0x004, xg(root_gosid, 0))]
    for gosid, sp, roots in spaces:
        # object group list
        og_nodes = [w.node(0x0B4, xg(g(int(uuid.UUID(gosid)) & 0xFFFF ^ 0x5000), 1)), w.node(0x022)]
        for idx, gg in enumerate(sp.table):
            og_nodes.append(w.node(0x024, struct.pack('<I', idx) + guid_bytes(gg)))
        og_nodes.append(w.node(0x028))
        for c, jcid, payload in sp.objects:
            if isinstance(payload, str):
                body = struct.pack('<II', c, jcid) + u8(1) + struct.pack('<I', len(payload)) + utf16(payload) + struct.pack('<I', 0)
                og_nodes.append(w.node(0x072, body))
            else:
                ref = w.chunk(object_prop_set(payload))
                og_nodes.append(w.node(0x0A4, struct.pack('<II', c, jcid) + u8(0) + u8(1), ref))
        og_nodes.append(w.node(0x0B8))
        og_ref = w.file_node_list(og_nodes)
        # revision manifest list
        rid = xg(g(int(uuid.UUID(gosid)) & 0xFFFF ^ 0x7000), 1)
        rev_nodes = [w.node(0x014, xg(gosid, 0) + u32(0)),
                     w.node(0x01E, rid + NIL_XG + u32(1) + u16(0)),
                     w.node(0x0B0, xg(g(int(uuid.UUID(gosid)) & 0xFFFF ^ 0x5000), 1), og_ref, base=2),
                     w.node(0x022), w.node(0x028)]
        if encrypted and gosid == root_gosid:
            rev_nodes.insert(1, w.node(0x07C, b'', w.chunk(b'\0' * 32)))
        for role, c in roots.items():
            rev_nodes.append(w.node(0x05A, xg(sp.oid_guid, c & 0xFF) + u32(role)))
        rev_nodes.append(w.node(0x01C))
        rev_ref = w.file_node_list(rev_nodes)
        osm_ref = w.file_node_list([w.node(0x00C, xg(gosid, 0)), w.node(0x010, b'', rev_ref, base=2)])
        root_nodes.append(w.node(0x008, xg(gosid, 0), osm_ref, base=2))
    if fds_list:
        root_nodes.append(w.node(0x090, b'', fds_list, base=2))
    root_ref = w.file_node_list(root_nodes)

    # transaction log: one transaction listing every file node list and its node count, then the sentinel
    entries = b''.join(struct.pack('<II', lid, cnt) for lid, cnt in sorted(w.list_counts.items()))
    entries += struct.pack('<II', 1, 0)
    tlog = entries + struct.pack('<QI', 0xFFFFFFFFFFFFFFFF, 0)
    tlog_ref = w.chunk(tlog)

    # header ([MS-ONESTORE] 2.3.1)
    h = bytearray(1024)
    h[0:16] = guid_bytes(TYPE_ONE)
    h[16:32] = guid_bytes(g(seed + 999))
    h[48:64] = guid_bytes(FORMAT)
    struct.pack_into('<IIII', h, 64, 0x2A, 0x2A, 0x2A, 0x2A)
    struct.pack_into('<II', h, 88, 0xFFFFFFFF, 0)       # fcrLegacyTransactionLog (fcrNil, 32-bit)
    struct.pack_into('<I', h, 96, 1)                    # cTransactionsInLog
    struct.pack_into('<II', h, 112, 0xFFFFFFFF, 0)      # fcrLegacyFileNodeListRoot = fcrNil
    struct.pack_into('<QI', h, 148, 0, 0)               # fcrHashedChunkList (zero)
    struct.pack_into('<QI', h, 160, tlog_ref[0], tlog_ref[1])
    struct.pack_into('<QI', h, 172, root_ref[0], root_ref[1])
    struct.pack_into('<QI', h, 184, 0, 0)               # fcrFreeChunkList
    struct.pack_into('<Q', h, 196, len(w.buf))          # cbExpectedFileLength
    h[212:228] = guid_bytes(g(seed + 998))
    w.buf[0:1024] = h
    return bytes(w.buf)


# ----------------------------------------------------------------------------------------------- sample pages

def page_lecture(sp, blobs):
    title_oe = oe(sp, [para(sp, [('Lecture 3 — Cell biology', {})], 'PageTitle')])
    title = sp.new(jcidTitleNode, [pid(ElementChildNodes, OidRef([outline(sp, 1.0, 0.6, 12, [title_oe])]))])

    intro = oe(sp, [para(sp, [('Key ideas: ', {'b': True}), ('membranes', {'i': True}), (' control ', {}),
                              ('transport', {'u': True, 'color': 0xC00000}), (' and ', {}), ('signalling', {'hl': 0xFFFF00}),
                              ('.', {'size': 14})])])
    link_code = '﷟HYPERLINK "https://example.com/cells"'
    link = oe(sp, [para(sp, [('Reading: ', {}), (link_code, {'link': True}), ('cell notes', {'link': True}), (' (online)', {})])])
    b1 = oe(sp, [para(sp, [('Phospholipid bilayer', {})])], list_fmt='•')
    b2c = oe(sp, [para(sp, [('Diffusion', {})])], list_fmt='�\u0000.')
    b2d = oe(sp, [para(sp, [('Active transport', {})])], list_fmt='�\u0000.')
    b2 = oe(sp, [para(sp, [('Proteins', {})])], children=[b2c, b2d], list_fmt='•')
    heading = oe(sp, [para(sp, [('Summary', {'b': True, 'size': 16})], 'h2')])
    out1 = outline(sp, 1.0, 2.4, 13, [heading, intro, link, b1, b2])

    def cell(text, bold=False):
        return sp.new(jcidTableCellNode, [pid(ElementChildNodes, OidRef([oe(sp, [para(sp, [(text, {'b': bold} if bold else {})])])]))])
    rows = []
    for r in [('Organelle', 'Function'), ('Nucleus', 'Holds DNA'), ('Mitochondria', 'Makes ATP')]:
        rows.append(sp.new(jcidTableRowNode, [pid(ElementChildNodes, OidRef([cell(r[0], r == ('Organelle', 'Function')), cell(r[1])]))]))
    table = sp.new(jcidTableNode, [pid(RowCount, u32(3)), pid(ColumnCount, u32(2)),
                                   pid(TableColumnWidths, u8(2) + f32(3.0) + f32(4.0)), pid(TableBordersVisible, True),
                                   pid(ElementChildNodes, OidRef(rows))])
    out2 = outline(sp, 1.0, 9.0, 9, [oe(sp, [table])])

    png_guid = g(0x1000 + sp.n)
    blobs[png_guid] = make_png(64, 48)
    pic = sp.file_object(jcidPictureContainer14, png_guid)
    image = sp.new(jcidImageNode, [pid(PictureContainer, OidRef([pic])), pid(PictureWidth, f32(4.0)), pid(PictureHeight, f32(3.0)),
                                   pid(OffH, f32(15.0)), pid(OffV, f32(2.4)), pid(ImageAltText, utf16('Cell diagram'))])

    # ink: two strokes, HIMETRIC deltas (first point absolute)
    def stroke(points, color, width_himetric):
        xs = [points[0][0]] + [points[i][0] - points[i - 1][0] for i in range(1, len(points))]
        ys = [points[0][1]] + [points[i][1] - points[i - 1][1] for i in range(1, len(points))]
        dims = guid_bytes(X_DIM) + struct.pack('<iiIf', 0, 100000, 1, 1000.0) + guid_bytes(Y_DIM) + struct.pack('<iiIf', 0, 100000, 1, 1000.0)
        props = sp.new(jcidStrokePropertiesNode, [pid(InkWidth, f32(width_himetric)), pid(InkHeight, f32(width_himetric)),
                                                   pid(InkColor, colorref(color)), pid(InkDimensions, dims)])
        return sp.new(jcidInkStrokeNode, [pid(InkPath, isf_signed(xs + ys)), pid(InkStrokeProperties, OidRef([props]))])
    circle = [(int(1500 + 1200 * __import__('math').cos(t / 12 * 6.283)), int(1500 + 900 * __import__('math').sin(t / 12 * 6.283))) for t in range(13)]
    # coordinates are stored relative to the InkDataNode bounding box origin (100, 200)
    shift = lambda pts: [(x + 100, y + 200) for x, y in pts]
    s1 = stroke(shift(circle), 0x1D4ED8, 70.0)
    s2 = stroke(shift([(200, 3200), (1500, 3500), (2800, 3100)]), 0xDC2626, 50.0)
    ink_data = sp.new(jcidInkDataNode, [pid(InkStrokes, OidRef([s1, s2])),
                                        pid(InkBoundingBox, struct.pack('<iiii', 100, 200, 3000, 3800))])
    ink = sp.new(jcidInkContainer, [pid(OffH, f32(15.0)), pid(OffV, f32(7.0)), pid(InkData, OidRef([ink_data])),
                                    pid(InkScalingX, f32(1.0)), pid(InkScalingY, f32(1.0))])

    att_guid = g(0x2000 + sp.n)
    blobs[att_guid] = b'Lab safety rules\n1. Goggles on.\n2. No food.\n'
    cont = sp.file_object(jcidEmbeddedFileContainer, att_guid)
    att = sp.new(jcidEmbeddedFileNode, [pid(EmbeddedFileContainer, OidRef([cont])), pid(EmbeddedFileName, utf16('safety.txt')),
                                        pid(OffH, f32(15.0)), pid(OffV, f32(12.0))])

    page = sp.new(jcidPageNode, [pid(PageWidth, f32(17.0)), pid(PageHeight, f32(22.0)),
                                 pid(StructureElementChildNodes, OidRef([title])),
                                 pid(ElementChildNodes, OidRef([out1, out2, image, ink, att])),
                                 pid(LastModifiedTime, u32(TIME32_2026 + 3600))])
    return page, 'Lecture 3 — Cell biology', 1


def page_arabic(sp, blobs):
    t = oe(sp, [para(sp, [('ملاحظات المحاضرة', {})], 'PageTitle')], rtl=True)
    title = sp.new(jcidTitleNode, [pid(ElementChildNodes, OidRef([outline(sp, 1.0, 0.6, 12, [t])]))])
    p1 = oe(sp, [para(sp, [('الخلية هي ', {}), ('وحدة الحياة', {'b': True}), (' الأساسية.', {})], align=2)], rtl=True)
    p2 = oe(sp, [para(sp, [('النواة', {})])], list_fmt='�\u0000.', rtl=True, restart=1)
    p3 = oe(sp, [para(sp, [('الميتوكوندريا', {})])], list_fmt='�\u0000.', rtl=True)
    out = outline(sp, 1.0, 2.4, 12, [p1, p2, p3])
    page = sp.new(jcidPageNode, [pid(StructureElementChildNodes, OidRef([title])), pid(ElementChildNodes, OidRef([out]))])
    return page, 'ملاحظات المحاضرة', 1


def page_sub(sp, blobs):
    t = oe(sp, [para(sp, [('Homework', {})], 'PageTitle')])
    title = sp.new(jcidTitleNode, [pid(ElementChildNodes, OidRef([outline(sp, 1.0, 0.6, 12, [t])]))])
    items = [oe(sp, [para(sp, [(s, {})])], list_fmt='�\u0000)') for s in ('Read chapter 4', 'Answer questions 1-5', 'Draw a cell')]
    out = outline(sp, 1.0, 2.4, 10, items)
    page = sp.new(jcidPageNode, [pid(StructureElementChildNodes, OidRef([title])), pid(ElementChildNodes, OidRef([out]))])
    return page, 'Homework', 2


def page_lab(sp, blobs):
    t = oe(sp, [para(sp, [('Lab 1 — Microscopes', {})], 'PageTitle')])
    title = sp.new(jcidTitleNode, [pid(ElementChildNodes, OidRef([outline(sp, 1.0, 0.6, 12, [t])]))])
    steps = [oe(sp, [para(sp, [(s, {})])], list_fmt='�\u0000.') for s in ('Clean the lens', 'Place the slide', 'Focus slowly')]
    out = outline(sp, 1.0, 2.4, 10, [oe(sp, [para(sp, [('Steps', {'b': True})])])] + steps)
    page = sp.new(jcidPageNode, [pid(StructureElementChildNodes, OidRef([title])), pid(ElementChildNodes, OidRef([out]))])
    return page, 'Lab 1 — Microscopes', 1


# ----------------------------------------------------------------------------------------------- CAB (.onepkg)

def make_cab(files, compress):
    """files: list of (name, bytes). One folder; MSZIP (compress=True) or stored blocks of 32 KB."""
    stream = b''.join(d for _, d in files)
    blocks = []
    hist = b''
    for i in range(0, max(len(stream), 1), 32768):
        chunk = stream[i:i + 32768]
        if compress:
            c = zlib.compressobj(9, zlib.DEFLATED, -15, zdict=hist) if hist else zlib.compressobj(9, zlib.DEFLATED, -15)
            data = b'CK' + c.compress(chunk) + c.flush(zlib.Z_FINISH)
            # self-check with an independent decoder path
            d = zlib.decompressobj(-15, zdict=hist) if hist else zlib.decompressobj(-15)
            assert d.decompress(data[2:]) == chunk
            hist = (hist + chunk)[-32768:]
        else:
            data = chunk
        blocks.append(struct.pack('<IHH', 0, len(data), len(chunk)) + data)
    cffiles = b''
    off = 0
    for name, d in files:
        raw = name.encode('utf-8') + b'\0'
        attribs = 0x20 | (0x80 if any(ord(ch) > 127 for ch in name) else 0)
        cffiles += struct.pack('<IIHHHH', len(d), off, 0, 0x5A21, 0x6000, attribs) + raw
        off += len(d)
    header_len = 36
    folder_len = 8
    coff_files = header_len + folder_len
    data_start = coff_files + len(cffiles)
    total = data_start + sum(len(b) for b in blocks)
    hdr = struct.pack('<4sIIIIIBBHHHHH', b'MSCF', 0, total, 0, coff_files, 0, 3, 1, 1, len(files), 0, 0x1234, 0)
    folder = struct.pack('<IHH', data_start, len(blocks), 1 if compress else 0)
    return hdr + folder + cffiles + b''.join(blocks)


def main():
    sample = build_section([page_lecture, page_sub, page_arabic], seed=0x100)
    lab = build_section([page_lab], seed=0x300)
    deleted = build_section([page_sub], seed=0x500)
    enc = build_section([page_lab], seed=0x700, encrypted=True)
    toc = bytearray(1024)
    toc[0:16] = guid_bytes(TYPE_TOC)
    toc[48:64] = guid_bytes(FORMAT)
    files = {
        'sample.one': sample,
        'lab.one': lab,
        'encrypted.one': enc,
        'toc.onetoc2': bytes(toc),
        'corrupt.one': sample[:3000],
    }
    pkg = [('Lectures.one', sample), ('Labs/Lab 1.one', lab), ('Open Notebook.onetoc2', bytes(toc)),
           ('OneNote_RecycleBin/Deleted.one', deleted)]
    files['sample.onepkg'] = make_cab(pkg, True)
    files['sample_stored.onepkg'] = make_cab(pkg, False)
    for name, data in files.items():
        with open(os.path.join(HERE, name), 'wb') as f:
            f.write(data)
        print('%-22s %7d bytes' % (name, len(data)))


if __name__ == '__main__':
    main()
