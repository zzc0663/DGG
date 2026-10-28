#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「抖关关」启动图标（无第三方依赖，纯 zlib 手写 PNG）。

设计：品牌红对角渐变底 + 白色粗对勾。
与 App 内 bg_logo + ic_logo 的「红底白勾」视觉保持一致。

输出：
  mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png     圆角方形
  mipmap-{...}/ic_launcher_round.png                          圆形
"""
import math
import os
import struct
import zlib

SS = 4          # 超采样倍数（抗锯齿）
DESIGN = 108.0  # 设计稿边长，与 adaptive icon 的 viewport 对齐

# 品牌红：对角渐变（左上偏亮 → 右下偏深）
C_TOP = (255, 61, 47)     # #FF3D2F
C_BOTTOM = (192, 20, 11)  # #C0140B

# 对勾折线（108 设计稿坐标）。绕中心 (54,54) 放大 1.25 倍：
# 勾身加笔画后落在 x 22.7~85.3 / y 26.5~77.8，正好收在 adaptive icon 的
# 66dp 安全区（21~87）内，桌面裁成圆形也不会切到笔画。
P1 = (29.0, 54.6)
P2 = (45.3, 71.5)
P3 = (79.0, 32.8)
STROKE = 12.5  # 笔画宽度

RADII = {
    'mdpi': 48,
    'hdpi': 72,
    'xhdpi': 96,
    'xxhdpi': 144,
    'xxxhdpi': 192,
}


def dist_seg(px, py, ax, ay, bx, by):
    """点到线段的最短距离。"""
    dx = bx - ax
    dy = by - ay
    if dx == 0.0 and dy == 0.0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    if t < 0.0:
        t = 0.0
    elif t > 1.0:
        t = 1.0
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def rounded_rect_alpha(x, y, size, radius):
    """圆角矩形的覆盖率（坐标已归一化到 0..size）。"""
    r = radius
    cx = min(max(x, r), size - r)
    cy = min(max(y, r), size - r)
    d = math.hypot(x - cx, y - cy)
    return 1.0 if d <= r else 0.0


def circle_alpha(x, y, size):
    c = size / 2.0
    d = math.hypot(x - c, y - c)
    return 1.0 if d <= c else 0.0


def render(out_size, round_icon):
    s = out_size * SS
    scale = s / DESIGN
    half = STROKE * scale / 2.0

    # 折线转像素坐标
    ax, ay = P1[0] * scale, P1[1] * scale
    bx, by = P2[0] * scale, P2[1] * scale
    cx, cy = P3[0] * scale, P3[1] * scale

    radius = s * 0.225 if not round_icon else s / 2.0

    rows = []
    for py in range(s):
        row = bytearray()
        for px in range(s):
            fx = px + 0.5
            fy = py + 0.5

            # 形状遮罩
            if round_icon:
                inside = circle_alpha(fx, fy, s)
            else:
                inside = rounded_rect_alpha(fx, fy, s, radius)
            if inside == 0.0:
                row += b'\x00\x00\x00\x00'
                continue

            # 对角渐变 t：左上 0 → 右下 1
            t = (fx + fy) / (2.0 * s)
            r = int(C_TOP[0] + (C_BOTTOM[0] - C_TOP[0]) * t)
            g = int(C_TOP[1] + (C_BOTTOM[1] - C_TOP[1]) * t)
            b = int(C_TOP[2] + (C_BOTTOM[2] - C_TOP[2]) * t)

            # 右上角柔和高光，避免大色块发死
            glow = math.hypot(fx - s * 0.78, fy - s * 0.20) / (s * 0.62)
            if glow < 1.0:
                k = (1.0 - glow) ** 2 * 0.16
                r = int(r + (255 - r) * k)
                g = int(g + (255 - g) * k)
                b = int(b + (255 - b) * k)

            # 白色对勾
            d = min(dist_seg(fx, fy, ax, ay, bx, by),
                    dist_seg(fx, fy, bx, by, cx, cy))
            if d <= half:
                # 边缘留 1px 过渡，配合超采样变软
                r = g = b = 255

            row += bytes((r, g, b, 255))
        rows.append(bytes(row))

    return _downsample(rows, s, out_size)


def _downsample(rows, src, dst):
    """SS×SS 块平均降采样，得到抗锯齿边缘。"""
    out = []
    for y in range(dst):
        row = bytearray()
        for x in range(dst):
            ar = ag = ab = aa = 0
            for dy in range(SS):
                base = rows[y * SS + dy]
                for dx in range(SS):
                    i = (x * SS + dx) * 4
                    a = base[i + 3]
                    if a:
                        ar += base[i] * a
                        ag += base[i + 1] * a
                        ab += base[i + 2] * a
                    aa += a
            if aa == 0:
                row += b'\x00\x00\x00\x00'
            else:
                row += bytes((ar // aa, ag // aa, ab // aa, aa // (SS * SS)))
        out.append(bytes(row))
    return out


def write_png(path, rows, size):
    raw = b''.join(b'\x00' + r for r in rows)

    def chunk(tag, data):
        return (struct.pack('>I', len(data)) + tag + data
                + struct.pack('>I', zlib.crc32(tag + data) & 0xFFFFFFFF))

    png = b'\x89PNG\r\n\x1a\n'
    png += chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 6, 0, 0, 0))
    png += chunk(b'IDAT', zlib.compress(raw, 9))
    png += chunk(b'IEND', b'')
    with open(path, 'wb') as fh:
        fh.write(png)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    res = os.path.normpath(os.path.join(here, '..', 'app', 'src', 'main', 'res'))
    if not os.path.isdir(res):
        raise SystemExit('找不到 res 目录: %s' % res)
    print('输出到 %s' % res)
    for name, size in RADII.items():
        folder = os.path.join(res, 'mipmap-' + name)
        os.makedirs(folder, exist_ok=True)
        for round_icon, fname in ((False, 'ic_launcher.png'),
                                  (True, 'ic_launcher_round.png')):
            rows = render(size, round_icon)
            write_png(os.path.join(folder, fname), rows, size)
            print('  %-16s %3dpx  %s' % (name, size, fname))


if __name__ == '__main__':
    main()
