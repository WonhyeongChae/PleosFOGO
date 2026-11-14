import numpy as np, subprocess, sys, math, os
from PIL import Image, ImageFilter

APP = os.environ.get('FOGO_INTRO') == 'app'  # app: 1680x1208 (Pleos Connect app area), else 1920x1080
W, H, S, FPS, DUR = (420, 302, 4, 30, 11.5) if APP else (480, 270, 4, 30, 11.5)
rng = np.random.default_rng(7)
def C(h): return np.array([int(h[i:i+2], 16) for i in (1, 3, 5)], float)
BG, DOT = C('#0A0F1E'), C('#182036')
LIME, LIME_HI, LIME_LO = C('#B8F53C'), C('#ECFFA6'), C('#86C81C')
CYAN, TEAL, WHITE = C('#4FD2FF'), C('#3ED6B0'), C('#FFFFFF')
SHADOW, OUT, OFF = C('#2448D8'), C('#050814'), C('#222B47')
LAND, SIDE = C('#2F3A63'), C('#4F7F2C')
ROADC, ROADL, YEL = C('#2B303C'), C('#3A4050'), C('#FFCB2E')

def rect(img, x, y, w, h, col, a=1.0):
    x0, y0, x1, y1 = max(0, int(x)), max(0, int(y)), min(W, int(x + w)), min(H, int(y + h))
    if x1 > x0 and y1 > y0:
        img[y0:y1, x0:x1] = img[y0:y1, x0:x1] * (1 - a) + col * a

# ---------- logo ----------
FONT = {'F': ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
        'O': ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
        'G': ['01110', '10001', '10000', '10111', '10001', '10001', '01110']}
BL, GAP = 12, 14
LW, LH = 5 * BL, 7 * BL
LX0 = (W - (4 * LW + 3 * GAP)) // 2
LY0 = 98 if APP else 100
LETTERS = 'FOGO'
def letter_mask(ch):
    m = np.zeros((LH, LW), bool)
    for r, row in enumerate(FONT[ch]):
        for c, v in enumerate(row):
            if v == '1': m[r*BL:(r+1)*BL, c*BL:(c+1)*BL] = True
    return m
LM = [letter_mask(c) for c in LETTERS]
def dilate(m, n):
    p = np.pad(m, n); out = np.zeros_like(p)
    for dy in range(-n, n + 1):
        for dx in range(-n, n + 1):
            out |= np.roll(np.roll(p, dy, 0), dx, 1)
    return out
def blit_mask(img, m, x, y, col, a=1.0):
    h, w = m.shape
    x0, y0 = max(0, x), max(0, y); x1, y1 = min(W, x + w), min(H, y + h)
    if x1 <= x0 or y1 <= y0: return
    sub = m[y0 - y:y1 - y, x0 - x:x1 - x]
    reg = img[y0:y1, x0:x1]
    reg[sub] = reg[sub] * (1 - a) + col * a
def edge_masks(m):
    up = np.roll(m, 2, 0); up[:2] = False
    lf = np.roll(m, 2, 1); lf[:, :2] = False
    dn = np.roll(m, -2, 0); dn[-2:] = False
    rt = np.roll(m, -2, 1); rt[:, -2:] = False
    hi = m & ~(up & lf); lo = m & ~(dn & rt) & ~hi
    return hi, lo
LE = [edge_masks(m) for m in LM]
LOGO_CACHE = {}
def logo_set(bl):
    """Letters at block size bl: (mask, outline, (highlight, lowlight)) per letter, outline px, letter gap."""
    if bl not in LOGO_CACHE:
        px = max(1, round(bl * 3 / BL))
        letters = []
        for ch in LETTERS:
            k = np.zeros((7 * bl, 5 * bl), bool)
            for r, row in enumerate(FONT[ch]):
                for c, v in enumerate(row):
                    if v == '1': k[r*bl:(r+1)*bl, c*bl:(c+1)*bl] = True
            letters.append((k, dilate(k, px), edge_masks(k)))
        LOGO_CACHE[bl] = letters, px, round(bl * GAP / BL)
    return LOGO_CACHE[bl]

# ---------- mascot sprite ----------
SPR = """
...........KKKKKK..........
.........KKWWWWWWKK........
........KWWWWWWWWWWK.......
.......KWWWWWWWWWWWWK......
.......KWWWWWWWWWWWWK......
......KWWWKKWWWWKKWWWK.....
......KWWWKKWWWWKKWWWK.....
......KWWWWWWWWWWWWWWK.....
......KWPPWWWKKWWWPPWK.....
......KWWWWWWWWWWWWWWK.....
.....KBKWWWWWWWWWWWWKBK....
....KBBWKKWWKKWWKKWWBBBK...
..KKBBBBBKKBBKKBBKKBBBBBK..
.KBBBBBBBBBBBBBBBBBBBBBBBK.
KBBBBBBBBBBBBBBBBBBBBBBBYYK
KBBBBBBBBBBBBBBBBBBBBBBBBBK
KBBBBDDDDBBBBBBBBBDDDDBBBBK
.KKKDDDDDDKKKKKKKDDDDDDKKK.
....DDGGDD.......DDGGDD....
.....DDDD.........DDDD.....
""".strip().split('\n')
PAL = {'K': C('#0B1228'), 'W': C('#FFFFFF'), 'P': C('#FF9DB0'), 'B': C('#1F5FB4'), 'Y': C('#FFCB2E'), 'D': C('#2A2D36'), 'G': C('#A0A6B4')}
SP = 3
SW, SH = len(SPR[0]) * SP, len(SPR) * SP
def draw_car(img, cx, by):
    x0, y0 = int(round(cx - SW / 2)), int(round(by - SH))
    for r, row in enumerate(SPR):
        for c, ch in enumerate(row):
            if ch != '.': rect(img, x0 + c * SP, y0 + r * SP, SP, SP, PAL[ch])

# ---------- car path ----------
ROAD_Y = 224 if APP else 226
LCX = [LX0 + i * (LW + GAP) + LW // 2 for i in range(4)]
TOP = LY0
def ease_out(u): return 1 - (1 - u) ** 2
def ease_in(u): return u * u
def ease_in_out(u): return u * u * (3 - 2 * u)
EXIT_X = LX0 + 4 * LW + 3 * GAP + 43
SEG = [  # (t0, t1, p0, p1, hop height, easing)
    (2.5, 3.3, (-40, ROAD_Y), (LX0 - 37, ROAD_Y), 0, ease_out),
    (3.3, 3.85, (LX0 - 37, ROAD_Y), (LCX[0], TOP), 30, None),
    (3.85, 4.3, (LCX[0], TOP), (LCX[1], TOP), 36, None),
    (4.3, 4.75, (LCX[1], TOP), (LCX[2], TOP), 36, None),
    (4.75, 5.2, (LCX[2], TOP), (LCX[3], TOP), 36, None),
    (5.2, 5.55, (LCX[3], TOP), (LCX[3], TOP), 24, None),
    (5.55, 6.1, (LCX[3], TOP), (EXIT_X, ROAD_Y), 30, None),
    (6.1, 6.75, (EXIT_X, ROAD_Y), (W + 60, ROAD_Y), 0, ease_in)]
LAND_EV = [(3.85, 0), (4.3, 1), (4.75, 2), (5.2, 3), (5.55, 3)]
def car_pos(t):
    for t0, t1, p0, p1, hh, ez in SEG:
        if t0 <= t < t1:
            u = (t - t0) / (t1 - t0)
            if ez: u = ez(u)
            x = p0[0] + (p1[0] - p0[0]) * u
            y = p0[1] + (p1[1] - p0[1]) * u - hh * 4 * u * (1 - u)
            if hh == 0: y -= (int(t * 12) % 2)
            return x, y
    return None
SPARKS = []
for tl, li in LAND_EV + [(6.1, -1)]:
    px, py = (LCX[li], TOP) if li >= 0 else (EXIT_X, ROAD_Y)
    for _ in range(9):
        SPARKS.append((tl, px + rng.uniform(-20, 20), py, rng.uniform(-70, 70), rng.uniform(-110, -40), rng.random() < .5))

# ---------- fog ----------
FB = 6
FGW, FGH = -(-W // FB), -(-H // FB)
bx, by_ = np.meshgrid(np.arange(FGW), np.arange(FGH))
fr = rng.random((FGH, FGW)); fr2 = rng.random((FGH, FGW))
fd = np.hypot((bx - (FGW - 1) / 2) / (FGW / 2), (by_ - (FGH - 1) / 2) / (FGH / 2)) / 1.414
F_TC = 0.35 + 1.75 * (0.75 * fd + 0.25 * fr)
edge = np.abs(bx - (FGW - 1) / 2)
F_KEEP = edge > (FGW / 2 - 6.5 + 5 * fr2 - 2.0 * np.abs(np.sin(by_ * 0.9)))
FSH = [C('#D3DBEB'), C('#B4C0D8'), C('#95A4C2'), C('#7B8BAC')]
def draw_fog(img, t):
    n = np.sin(bx * 0.45 + t * 0.8) + np.sin(by_ * 0.6 - t * 0.5) + np.sin((bx + by_) * 0.28 + t * 0.35) + fr * 1.2
    idx = np.clip(((n + 3.2) / 6.4 * 4).astype(int), 0, 3)
    for j in range(FGH):
        for i in range(FGW):
            keep = F_KEEP[j, i]
            dt = t - F_TC[j, i]
            if keep or dt < 0: s = FB
            elif dt < 0.07: s = 4
            elif dt < 0.14: s = 2
            else: continue
            o = (FB - s) // 2
            a = 1.0 if not keep else (0.92 if t < 6.5 else 0.8)
            rect(img, i * FB + o, j * FB + o, s, s, FSH[idx[j, i]], a)

# ---------- map ----------
CS, CSX, ROWS, MY0 = 5, 6, 50, (8 if APP else 10)
LAT_TOP, LAT_SPAN, LONC = 38.65, 5.6, 127.8
DLON = (LAT_SPAN / ROWS) / 0.81
def ll2cell(lon, lat):
    return (lat - LAT_TOP) / -LAT_SPAN * ROWS, (lon - LONC) / DLON
COLS = 30
# Map sits on the right; the logo slides into the space on its left (see frame()).
CX0 = W - 44 - COLS * CSX
SLIDE_T0, SLIDE_DUR, END_BL = 6.3, 0.7, 6
END_W = 4 * 5 * END_BL + 3 * round(END_BL * GAP / BL)
END_X, END_Y = (40 + CX0 - END_W) // 2, MY0 + 125 - 7 * END_BL // 2
POLY = [(126.7,37.95),(127.1,38.3),(127.8,38.3),(128.35,38.6),(128.6,38.2),(129.0,37.7),(129.35,37.1),(129.45,36.5),(129.4,36.05),(129.55,36.0),(129.45,35.5),(129.2,35.15),(128.9,35.05),(128.6,34.9),(128.4,34.85),(128.0,34.9),(127.75,34.75),(127.5,34.85),(127.3,34.6),(126.9,34.4),(126.5,34.35),(126.3,34.6),(126.4,35.0),(126.35,35.4),(126.6,35.8),(126.5,36.1),(126.6,36.4),(126.3,36.6),(126.15,36.8),(126.5,36.95),(126.85,36.95),(126.7,37.25),(126.55,37.55),(126.4,37.75)]
def inpoly(x, y, P):
    ins = False
    for i in range(len(P)):
        x1, y1 = P[i]; x2, y2 = P[(i + 1) % len(P)]
        if (y1 > y) != (y2 > y) and x < (x2 - x1) * (y - y1) / (y2 - y1) + x1: ins = not ins
    return ins
land = np.zeros((ROWS, COLS), bool)
for r in range(ROWS):
    for c in range(COLS):
        lat = LAT_TOP - (r + .5) * LAT_SPAN / ROWS; lon = LONC + (c - COLS / 2 + .5) * DLON
        land[r, c] = inpoly(lon, lat, POLY) or ((lon - 126.55) / 0.40) ** 2 + ((lat - 33.38) / 0.19) ** 2 <= 1
CITY = dict(seoul=(126.98,37.57), chuncheon=(127.73,37.88), sokcho=(128.55,38.2), gangneung=(128.88,37.75), daejeon=(127.38,36.35), daegu=(128.6,35.87), busan=(129.05,35.2), jeonju=(127.15,35.82), gwangju=(126.85,35.16), mokpo=(126.45,34.82), yeosu=(127.66,34.8), pohang=(129.33,36.03), andong=(128.73,36.57), wonju=(127.95,37.34), incheon=(126.65,37.45), taean=(126.35,36.75), jeju1=(126.25,33.35), jeju2=(126.55,33.5), jeju3=(126.88,33.4), jeju4=(126.55,33.28), tongyeong=(128.43,34.87), jinju=(128.1,35.18), samcheok=(129.16,37.44), uljin=(129.38,36.99))
ROUTES = [(7.15, ['seoul','wonju','gangneung']), (7.15, ['seoul','daejeon','daegu','busan']),
          (7.5, ['seoul','chuncheon','sokcho','gangneung']), (7.6, ['daejeon','jeonju','gwangju','mokpo']),
          (7.9, ['gangneung','samcheok','uljin','pohang','busan']), (8.0, ['seoul','incheon']), (8.1, ['daejeon','taean']),
          (8.2, ['gwangju','yeosu','jinju','busan']), (8.4, ['wonju','andong','daegu']), (8.5, ['jinju','tongyeong']),
          (8.7, ['jeju1','jeju2','jeju3','jeju4','jeju1'])]
lit = np.full((ROWS, COLS), 99.0); side = np.full((ROWS, COLS), 99.0)
for t0, names in ROUTES:
    cells = []
    for a, b in zip(names, names[1:]):
        (r0, c0), (r1, c1) = ll2cell(*CITY[a]), ll2cell(*CITY[b])
        n = int(max(abs(r1 - r0), abs(c1 - c0)) * 4) + 2
        for k in range(n + 1):
            rc = (int(math.floor(r0 + (r1 - r0) * k / n)), int(math.floor(c0 + (c1 - c0) * k / n + COLS / 2)))
            if rc not in cells: cells.append(rc)
    for k, (r, c) in enumerate(cells):
        tt = t0 + k * 0.03
        if 0 <= r < ROWS and 0 <= c < COLS:
            lit[r, c] = min(lit[r, c], tt)
            for dr, dc in ((1,0),(-1,0),(0,1),(0,-1)):
                rr, cc = r + dr, c + dc
                if 0 <= rr < ROWS and 0 <= cc < COLS and land[rr, cc]: side[rr, cc] = min(side[rr, cc], tt + 0.08)
mr = rng.random((ROWS, COLS))
LAND_T = 6.55 + 0.55 * (np.arange(ROWS)[:, None] / ROWS) + 0.15 * mr
def draw_map(img, glow, t):
    for r in range(ROWS):
        for c in range(COLS):
            x, y = CX0 + c * CSX, MY0 + r * CS
            if lit[r, c] <= t:
                age = t - lit[r, c]
                col = WHITE if age < 0.07 else (CYAN if age < 0.22 else (TEAL if mr[r, c] < 0.22 else LIME))
                rect(img, x, y, CSX - 1, CS - 1, col)
                rect(glow, x, y, CSX - 1, CS - 1, col, 0.55 if age > 0.22 else 1.0)
            elif land[r, c] and LAND_T[r, c] <= t:
                if side[r, c] <= t: rect(img, x, y, CSX - 1, CS - 1, SIDE)
                else:
                    a = 1.0; col = LAND if t - LAND_T[r, c] > 0.08 else C('#5A6A9A')
                    rect(img, x, y, CSX - 1, CS - 1, col, a)

# ---------- magnets ----------
ICON = {
 'seoul': ("#2448D8", ["....WW....","....WW....","...WWWW...","...WWWW...","....WW....","....WW....","....WW....","...WWWW...","..YYYYYY..",".YYYYYYYY."]),
 'gangwon': ("#39B8E8", ["....W.....","...WWW....","...WGW..W.","..GGGGG.WW","..GGGGGGGG",".GGGGGGGGG",".GGGGGGGGG","GGGGGGGGGG","GGGGGGGGGG","GGGGGGGGGG"]),
 'busan': ("#FF7A59", ["..........","...YYY....","..YYYYY...","...YYY....","..........","W..WW..WW.",".WW..WW..W","..........",".WW..WW..W","W..WW..WW."]),
 'jeju': ("#1E9E6A", ["......GG..",".....GG...","...OOOO...","..OOOOOO..",".OOOOOOOO.",".OOWOOOOO.",".OOOOOOOO.",".OOOOOOOO.","..OOOOOO..","...OOOO..."])}
IPAL = {'W': WHITE, 'Y': YEL, 'G': C('#1F7A4C'), 'O': C('#FF9A1F')}
ICON['gangwon'] = (ICON['gangwon'][0], ICON['gangwon'][1]);
def magnet_img(name):
    bg, rows = ICON[name]
    m = np.zeros((16, 16, 3)); m[:] = OUT
    m[1:15, 1:15] = WHITE; m[2:14, 2:14] = C(bg)
    for r, row in enumerate(rows):
        for c, ch in enumerate(row):
            if ch != '.': m[3 + r, 3 + c] = IPAL[ch] if not (name == 'gangwon' and ch == 'G') else C('#0E5E8C')
    return m.repeat(2, 0).repeat(2, 1)
def cpos(name, dy=0):
    r, c = ll2cell(*CITY[name]); return int(CX0 + (c + COLS / 2) * CSX), int(MY0 + r * CS) + dy
g, b, se, jj = cpos('gangneung'), cpos('busan'), cpos('seoul'), cpos('jeju2')
MAGS = [(9.35, 'gangwon', (g[0] + 20, g[1] - 14)), (9.58, 'busan', (b[0] + 22, b[1] + 16)),
        (9.81, 'seoul', (se[0] - 26, se[1] - 12)), (10.04, 'jeju', (jj[0] + 34, jj[1] - 10))]
MIMG = {n: magnet_img(n) for _, n, _ in MAGS}
def draw_magnets(img, glow, t):
    for t0, n, (cx, cy) in MAGS:
        dt = t - t0
        if dt < 0: continue
        sc = 1 if dt >= 0.12 else 1 + 2.2 * (1 - dt / 0.12)
        m = MIMG[n]
        if sc > 1:
            k = int(round(32 * sc)); m = np.asarray(Image.fromarray(m.astype(np.uint8)).resize((k, k), Image.NEAREST), float)
        k = m.shape[0]; x0, y0 = cx - k // 2, cy - k // 2
        if sc == 1: rect(img, x0 + 3, y0 + 3, 32, 32, OUT, 0.6)
        xa, ya, xb, yb = max(0, x0), max(0, y0), min(W, x0 + k), min(H, y0 + k)
        img[ya:yb, xa:xb] = m[ya - y0:yb - y0, xa - x0:xb - x0]
        if 0.12 <= dt < 0.4:
            rr = int(18 + (dt - 0.12) * 130); a = 1 - (dt - 0.12) / 0.28
            for (x, y, w, h) in ((cx - rr, cy - rr, 2 * rr, 1), (cx - rr, cy + rr, 2 * rr, 1), (cx - rr, cy - rr, 1, 2 * rr), (cx + rr, cy - rr, 1, 2 * rr + 1)):
                rect(img, x, y, w, h, WHITE, a); rect(glow, x, y, w, h, WHITE, a)

# ---------- frame ----------
LIT_T = [1.30, 1.46, 1.62, 1.78]
def letter_on(i, t):
    dt = t - LIT_T[i]
    if dt < 0: return 0.0
    if dt < 0.05: return 1.0
    if dt < 0.11: return 0.15
    if dt < 0.16: return 1.0
    if dt < 0.20: return 0.4
    return 1.0
def frame(t):
    img = np.empty((H, W, 3)); img[:] = BG
    img[5::10, 5::10] = DOT
    glow = np.zeros((H, W, 3))
    shake = 0
    for t0, _, _ in MAGS:
        if 0.12 <= t - t0 < 0.2: shake = 1 if int((t - t0) * 60) % 2 else -1
    if t >= 6.5: draw_map(img, glow, t)
    # road
    if 2.1 <= t < 7.1:
        x1 = W * min(1, (t - 2.1) / 0.4); x0 = W * max(0, (t - 6.6) / 0.4)
        if x1 > x0:
            rect(img, x0, ROAD_Y, x1 - x0, 14, ROADC); rect(img, x0, ROAD_Y, x1 - x0, 1, ROADL)
            for dx in range(0, W, 24):
                a, b = max(dx, x0), min(dx + 14, x1)
                if b > a: rect(img, a, ROAD_Y + 6, b - a, 2, YEL)
    # logo: neon letters that squash when the car lands; from SLIDE_T0 they slide left and shrink
    # so the map owns the right half, staying solid the whole time (no hollow outline over the routes).
    pulse = 0.85 + 0.15 * math.sin(t * 5)
    if t > 10.3: pulse = 1 + 0.5 * max(0, 1 - (t - 10.3) / 0.5)
    u = ease_in_out(min(1, max(0, (t - SLIDE_T0) / SLIDE_DUR)))
    bl = int(round(BL + (END_BL - BL) * u))
    ox, oy = int(round(LX0 + (END_X - LX0) * u)), int(round(LY0 + (END_Y - LY0) * u))
    letters, px, gap = logo_set(bl)
    for i, (k, k_out, (k_hi, k_lo)) in enumerate(letters):
        on = letter_on(i, t)
        sq = fl = 0.0
        for tl, li in LAND_EV:
            if li == i and 0 <= t - tl < 0.3:
                sq = max(sq, 4 * (1 - (t - tl) / 0.18)); fl = max(fl, 1 - (t - tl) / 0.3)
        x, y = ox + i * (5 * bl + gap), oy + int(max(0, sq)) + shake
        blit_mask(img, k_out, x - px, y - px, OUT)
        blit_mask(img, k_out, x + 1, y + 1, OUT)
        if on > 0: blit_mask(img, k, x + px + 1, y + px + 1, SHADOW, on)
        blit_mask(img, k, x, y, OFF)
        if on > 0:
            base = LIME * (1 - fl) + WHITE * fl
            blit_mask(img, k, x, y, base, on)
            blit_mask(img, k_hi, x, y, LIME_HI * (1 - fl) + WHITE * fl, on)
            blit_mask(img, k_lo, x, y, LIME_LO * (1 - fl) + WHITE * fl, on)
            blit_mask(glow, k, x, y, base, on * min(1.6, pulse + fl))
    # sparks
    for tl, px, py, vx, vy, w in SPARKS:
        dt = t - tl
        if 0 <= dt < 0.45:
            x, y = px + vx * dt, py + vy * dt + 260 * dt * dt
            col = WHITE if w else LIME
            rect(img, x, y, 2, 2, col); rect(glow, x, y, 2, 2, col)
    if t >= 9.3: draw_magnets(img, glow, t)
    p = car_pos(t)
    if p:
        if p[1] >= ROAD_Y - 1 and int(t * 15) % 2 == 0: rect(img, p[0] - SW / 2 - 6, ROAD_Y - 3, 3, 3, C('#8E9DBA'), 0.8)
        draw_car(img, p[0], p[1])
    draw_fog(img, t)
    # upscale + glow
    g8 = Image.fromarray(np.clip(glow, 0, 255).astype(np.uint8))
    g1 = np.asarray(g8.filter(ImageFilter.GaussianBlur(5)).resize((W * S, H * S), Image.BILINEAR), float)
    g2 = np.asarray(g8.filter(ImageFilter.GaussianBlur(16)).resize((W * S, H * S), Image.BILINEAR), float)
    big = img.repeat(S, 0).repeat(S, 1) + 0.55 * g1 + 0.5 * g2
    if t < 0.3: big *= t / 0.3
    return np.clip(big, 0, 255).astype(np.uint8)

if __name__ == '__main__':
    if sys.argv[1] == 'still':
        for t in sys.argv[2:]:
            Image.fromarray(frame(float(t))).save(f'/tmp/still_{t}.jpg', quality=88)
    else:
        out = sys.argv[2]
        p = subprocess.Popen(['ffmpeg', '-y', '-loglevel', 'error', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-s', f'{W*S}x{H*S}', '-r', str(FPS), '-i', '-',
                              '-c:v', 'libx264', '-crf', '19' if APP else '16', '-preset', 'slow', '-profile:v', 'main', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', out], stdin=subprocess.PIPE)
        for f in range(int(DUR * FPS)):
            p.stdin.write(frame(f / FPS).tobytes())
        p.stdin.close(); sys.exit(p.wait())
