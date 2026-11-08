# -*- coding: utf-8 -*-
"""WatchLink 布局示意图（非真机截图）。

手表页按 466×466 圆屏 / 2x 密度（233dp）绘制，并对靠近圆边的元素做等比缩小，
近似 Compose 里 ScalingLazyColumn 的曲率效果；手机页按 1080×2340 的等比缩略绘制。
"""
import math
from PIL import Image, ImageDraw, ImageFont

W = H = 466
R = W / 2.0
BG = (0, 0, 0)
SURFACE = (0x15, 0x1B, 0x26)
SURFACE_ALT = (0x1F, 0x28, 0x38)
TRACK = (0x26, 0x30, 0x3F)
TEXT = (0xF2, 0xF6, 0xFF)
DIM = (0x9A, 0xA8, 0xBF)
ACCENT = (0x3D, 0xDC, 0x97)
BLUE = (0x7F, 0xB2, 0xFF)
AMBER = (0xFF, 0xC5, 0x5C)

FONT = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"


def f(size, bold=False):
    return ImageFont.truetype(FONT_BOLD if bold else FONT, max(6, int(size)))


def dp(v):
    return v * 2.0


def chord(dy):
    dy = abs(dy)
    return 2 * math.sqrt(max(0.0, (R - 6) ** 2 - dy ** 2))


def run_page(elements, bg=BG):
    """elements: list of (height_dp, draw_fn(d, cy, k))，垂直居中堆叠并做圆屏缩放。"""
    img = Image.new("RGB", (W, H), bg)
    d = ImageDraw.Draw(img)
    gap = dp(4)
    total = sum(h for h, _ in elements) + gap * max(0, len(elements) - 1)
    y = (H - total) / 2.0
    for h, fn in elements:
        cy = y + h / 2.0
        fn(d, cy, max(0.86, min(1.0, chord(cy) / (W * 0.98))))
        y += h + gap
    return img


# ------------------------------- 手表第 1 页 -------------------------------

def ring(d, cy, k):
    r = dp(38) * k
    cx = W / 2
    width = max(3, int(dp(3.5) * k))
    box = [cx - r, cy - r, cx + r, cy + r]
    d.arc(box, 0, 360, fill=TRACK, width=width)
    d.arc(box, -90, -90 + 360 * 0.87, fill=ACCENT, width=width)
    d.text((cx, cy - dp(4) * k), "87%", font=f(dp(11.5) * k, True), fill=TEXT, anchor="mm")
    d.text((cx, cy + dp(12) * k), "充电中", font=f(dp(4.5) * k), fill=ACCENT, anchor="mm")


def stat_row(label, value, accent):
    def draw(d, cy, k):
        w = dp(205) * k
        h = dp(24) * k
        d.rounded_rectangle([W / 2 - w / 2, cy - h / 2, W / 2 + w / 2, cy + h / 2],
                            radius=dp(13) * k, fill=SURFACE)
        d.text((W / 2 - w / 2 + dp(11) * k, cy), label, font=f(dp(5) * k), fill=DIM, anchor="lm")
        d.text((W / 2 + w / 2 - dp(11) * k, cy), value, font=f(dp(6.5) * k, True), fill=accent, anchor="rm")
    return draw


def centered_text(s, size, color, bold=False):
    def draw(d, cy, k):
        d.text((W / 2, cy), s, font=f(dp(size) * k, bold), fill=color, anchor="mm")
    return draw


def chip(s, color):
    def draw(d, cy, k):
        fnt = f(dp(4.5) * k)
        tw = d.textlength(s, font=fnt)
        w = tw + dp(12) * k
        h = dp(18) * k
        d.rounded_rectangle([W / 2 - w / 2, cy - h / 2, W / 2 + w / 2, cy + h / 2],
                            radius=dp(9) * k, fill=SURFACE)
        d.text((W / 2, cy), s, font=fnt, fill=color, anchor="mm")
    return draw


def page_status(current=0):
    els = [
        (dp(6), centered_text("实时状态", 6, DIM)),
        (dp(76), ring),
        (dp(24), stat_row("WiFi", "Office-5G", ACCENT)),
        (dp(24), stat_row("铃声", "震动", BLUE)),
        (dp(24), stat_row("通知", "3 条", BLUE)),
        (dp(5), centered_text("免打扰关 · 更新 17:22", 4.5, DIM)),
        (dp(18), chip("已连接 iQOO Neo10", ACCENT)),
    ]
    img = run_page(els)
    dots(ImageDraw.Draw(img), current)
    return img


# ------------------------------- 手表第 2 页 -------------------------------

def notif_card(app, title, body, tm):
    def draw(d, cy, k):
        w = dp(213) * k
        h = dp(58) * k
        x0 = W / 2 - w / 2
        d.rounded_rectangle([x0, cy - h / 2, x0 + w, cy + h / 2], radius=dp(16) * k, fill=SURFACE)
        badge = dp(15) * k
        bx = x0 + dp(10) * k
        by = cy - h / 2 + dp(8) * k
        d.rounded_rectangle([bx, by, bx + badge, by + badge], radius=dp(5) * k, fill=SURFACE_ALT)
        d.text((bx + badge / 2, by + badge / 2), app[0], font=f(dp(4) * k, True), fill=BLUE, anchor="mm")
        d.text((bx + badge + dp(5) * k, by + badge / 2), app, font=f(dp(4.5) * k), fill=DIM, anchor="lm")
        d.text((x0 + w - dp(10) * k, by + badge / 2), tm, font=f(dp(4) * k), fill=DIM, anchor="rm")
        d.text((x0 + dp(10) * k, by + badge + dp(3) * k), title,
               font=f(dp(6) * k, True), fill=TEXT, anchor="la")
        d.text((x0 + dp(10) * k, by + badge + dp(18) * k), body,
               font=f(dp(5) * k), fill=DIM, anchor="la")
    return draw


def media_card(title, artist, playing, vol):
    def draw(d, cy, k):
        w = dp(216) * k
        h = dp(100) * k
        x0 = W / 2 - w / 2
        top = cy - h / 2
        d.rounded_rectangle([x0, top, x0 + w, top + h], radius=dp(16) * k, fill=SURFACE)
        d.text((x0 + dp(11) * k, top + dp(9) * k), "正在播放" if playing else "已暂停",
               font=f(dp(8) * k), fill=ACCENT, anchor="la")
        d.text((x0 + dp(11) * k, top + dp(21) * k), title, font=f(dp(12) * k, True), fill=TEXT, anchor="la")
        d.text((x0 + dp(11) * k, top + dp(38) * k), artist, font=f(dp(8.4) * k), fill=DIM, anchor="la")

        bw = (w - dp(22) * k - dp(8) * k) / 2.7
        by = top + dp(53) * k
        bh = dp(16) * k
        labels = ["上一首", "暂停" if playing else "播放", "下一首"]
        for i, lab in enumerate(labels):
            bx = x0 + dp(11) * k + i * (bw + dp(4) * k)
            d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=dp(10) * k, fill=SURFACE_ALT)
            d.text((bx + bw / 2, by + bh / 2), lab, font=f(dp(9) * k), fill=TEXT, anchor="mm")

        vy = top + dp(76) * k
        d.rounded_rectangle([x0 + dp(11) * k, vy, x0 + dp(11) * k + dp(26) * k, vy + dp(16) * k],
                            radius=dp(10) * k, fill=SURFACE_ALT)
        d.text((x0 + dp(11) * k + dp(13) * k, vy + dp(8) * k), "−", font=f(dp(10) * k), fill=TEXT, anchor="mm")
        d.text((x0 + w / 2, vy + dp(8) * k), f"音量 {vol}%", font=f(dp(8.4) * k), fill=DIM, anchor="mm")
        d.rounded_rectangle([x0 + w - dp(37) * k, vy, x0 + w - dp(11) * k, vy + dp(16) * k],
                            radius=dp(10) * k, fill=SURFACE_ALT)
        d.text((x0 + w - dp(24) * k, vy + dp(8) * k), "+", font=f(dp(10) * k), fill=TEXT, anchor="mm")
    return draw


def page_notifications(current=1):
    els = [
        (dp(100), media_card("晴天", "周杰伦 · QQ音乐", True, 60)),
        (dp(6), centered_text("通知 · 2", 6, DIM)),
        (dp(58), notif_card("微信", "张三", "晚上一起吃饭？我订好位子了", "17:18")),
    ]
    img = run_page(els)
    dots(ImageDraw.Draw(img), current)
    return img


# ------------------------------- 手表第 3 页 -------------------------------

def transfer_card():
    def draw(d, cy, k):
        w = dp(213) * k
        h = dp(50) * k
        x0 = W / 2 - w / 2
        d.rounded_rectangle([x0, cy - h / 2, x0 + w, cy + h / 2], radius=dp(16) * k, fill=SURFACE)
        d.text((x0 + dp(11) * k, cy - h / 2 + dp(8) * k), "IMG_20261108.jpg",
               font=f(dp(5.5) * k), fill=TEXT, anchor="la")
        bx0 = x0 + dp(11) * k
        bx1 = x0 + w - dp(11) * k
        by = cy + dp(2) * k
        d.rounded_rectangle([bx0, by, bx1, by + dp(2.5) * k], radius=dp(2) * k, fill=TRACK)
        d.rounded_rectangle([bx0, by, bx0 + (bx1 - bx0) * 0.46, by + dp(2.5) * k],
                            radius=dp(2) * k, fill=ACCENT)
        d.text((x0 + dp(11) * k, cy + dp(10) * k), "46% · 1.2 MB",
               font=f(dp(4) * k), fill=DIM, anchor="la")
    return draw


def file_card(name, meta):
    def draw(d, cy, k):
        w = dp(216) * k
        h = dp(58) * k
        x0 = W / 2 - w / 2
        top = cy - h / 2 + dp(7) * k
        d.rounded_rectangle([x0, cy - h / 2, x0 + w, cy + h / 2], radius=dp(16) * k, fill=SURFACE)
        d.text((x0 + dp(10) * k, top), name, font=f(dp(5.6) * k, True), fill=TEXT, anchor="la")
        d.text((x0 + dp(10) * k, top + dp(11) * k), meta, font=f(dp(8.4) * k * 0.5), fill=DIM, anchor="la")
        d.text((x0 + dp(10) * k, top + dp(19) * k), "保存在 Android/data/com.watchlink.wear/files/incoming",
               font=f(dp(8.4) * k * 0.45), fill=(0x5A, 0x67, 0x7D), anchor="la")
        d.text((x0 + dp(10) * k, top + dp(27) * k), "点按打开 · 长按删除",
               font=f(dp(8.4) * k * 0.5), fill=ACCENT, anchor="la")
    return draw


def page_files(current=2):
    els = [
        (dp(6), centered_text("文件 · 2", 6, DIM)),
        (dp(22), chip("发送文件到手机", ACCENT)),
        (dp(50), transfer_card()),
        (dp(58), file_card("行程单.pdf", "来自 iQOO Neo10 · 842.0 KB · 16:40")),
        (dp(58), file_card("会议纪要.docx", "来自 iQOO Neo10 · 36.5 KB · 15:02")),
    ]
    img = run_page(els)
    dots(ImageDraw.Draw(img), current)
    return img


def dots(d, current):
    cy = H - dp(8)
    for i in range(3):
        r = dp(3) if i == current else dp(2)
        cx = W / 2 + (i - 1) * dp(9)
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=ACCENT if i == current else TRACK)


# ------------------------------- 手机页 -------------------------------

def phone_page():
    pw, ph = 432, 936
    img = Image.new("RGB", (pw, ph), (0x0B, 0x0F, 0x17))
    d = ImageDraw.Draw(img)
    m = 16

    def card(y0, y1):
        d.rounded_rectangle([m, y0, pw - m, y1], radius=18, fill=(0x12, 0x18, 0x26))

    # 头部
    d.rounded_rectangle([m, 20, m + 40, 60], radius=12, fill=ACCENT)
    d.text((m + 20, 40), "W", font=f(20, True), fill=(4, 19, 12), anchor="mm")
    d.text((m + 50, 28), "WatchLink", font=f(21, True), fill=(0xE8, 0xEE, 0xF7), anchor="la")
    d.text((m + 50, 52), "手机 → 手表 · 实时同步", font=f(10), fill=DIM, anchor="la")

    # 连接卡
    y = 74
    card(y, y + 62)
    d.ellipse([m + 16, y + 27, m + 25, y + 36], fill=ACCENT)
    d.text((m + 33, y + 15), "已连接手表", font=f(12, True), fill=(0xE8, 0xEE, 0xF7), anchor="la")
    d.text((m + 33, y + 36), "OPPO Watch X2", font=f(10), fill=DIM, anchor="la")
    d.text((pw - m - 16, y + 30), "刷新", font=f(10), fill=ACCENT, anchor="rm")

    # 状态卡
    y += 74
    card(y, y + 268)
    d.text((m + 16, y + 14), "实时状态", font=f(14, True), fill=(0xE8, 0xEE, 0xF7), anchor="la")
    d.text((pw - m - 16, y + 18), "已同步到手表", font=f(9), fill=DIM, anchor="ra")
    d.text((m + 16, y + 44), "87", font=f(38, True), fill=ACCENT, anchor="la")
    d.text((m + 66, y + 62), "%", font=f(16), fill=ACCENT, anchor="la")
    d.text((m + 84, y + 52), "充电中", font=f(11), fill=(0xE8, 0xEE, 0xF7), anchor="la")
    d.text((m + 84, y + 70), "温度 33.4°C", font=f(9), fill=DIM, anchor="la")
    d.rounded_rectangle([m + 16, y + 96, pw - m - 16, y + 102], radius=3, fill=SURFACE_ALT)
    d.rounded_rectangle([m + 16, y + 96, m + 16 + (pw - 2 * m - 32) * 0.87, y + 102], radius=3, fill=ACCENT)

    tiles = [("WiFi", "已开启", "Office-5G", ACCENT),
             ("铃声", "震动", "免打扰关闭", BLUE),
             ("通知", "3 条", "最近 17:22", BLUE)]
    tw = (pw - 2 * m - 32 - 16) / 3
    for i, (label, value, hint, color) in enumerate(tiles):
        x0 = m + 16 + i * (tw + 8)
        d.rounded_rectangle([x0, y + 118, x0 + tw, y + 190], radius=14, fill=SURFACE_ALT)
        d.text((x0 + 11, y + 128), label, font=f(9), fill=DIM, anchor="la")
        d.text((x0 + 11, y + 150), value, font=f(13, True), fill=color, anchor="la")
        d.text((x0 + 11, y + 172), hint, font=f(8), fill=DIM, anchor="la")

    # 通知卡
    y += 282
    card(y, y + 168)
    d.text((m + 16, y + 14), "通知", font=f(14, True), fill=(0xE8, 0xEE, 0xF7), anchor="la")
    d.text((pw - m - 16, y + 18), "3 条", font=f(9), fill=DIM, anchor="ra")
    rows = [("微信", "张三", "晚上一起吃饭？我订好位子了", "17:18"),
            ("短信", "10086", "您本月流量已使用 80%", "17:10")]
    ry = y + 44
    for app, title, body, tm in rows:
        d.rounded_rectangle([m + 16, ry, m + 46, ry + 30], radius=9, fill=SURFACE_ALT)
        d.text((m + 31, ry + 15), app[0], font=f(11, True), fill=BLUE, anchor="mm")
        d.text((m + 56, ry - 2), app, font=f(8.5), fill=DIM, anchor="la")
        d.text((pw - m - 16, ry - 2), tm, font=f(8.5), fill=DIM, anchor="ra")
        d.text((m + 56, ry + 10), title, font=f(11, True), fill=(0xE8, 0xEE, 0xF7), anchor="la")
        d.text((m + 56, ry + 24), body, font=f(9), fill=DIM, anchor="la")
        ry += 56

    # 文件卡
    y += 182
    card(y, y + 118)
    d.text((m + 16, y + 14), "文件传输", font=f(14, True), fill=(0xE8, 0xEE, 0xF7), anchor="la")
    d.text((pw - m - 16, y + 18), "手机 → 手表", font=f(9), fill=DIM, anchor="ra")
    d.rounded_rectangle([m + 16, y + 40, pw - m - 16, y + 76], radius=12, fill=ACCENT)
    d.text((pw / 2, y + 58), "选择文件并发送", font=f(12, True), fill=(4, 19, 12), anchor="mm")
    d.text((m + 16, y + 88), "IMG_20261108.jpg", font=f(8.5), fill=(0xE8, 0xEE, 0xF7), anchor="la")
    d.rounded_rectangle([m + 16, y + 102, pw - m - 16, y + 106], radius=2, fill=SURFACE_ALT)
    d.rounded_rectangle([m + 16, y + 102, m + 16 + (pw - 2 * m - 32) * 0.46, y + 106], radius=2, fill=BLUE)
    return img


def main():
    pages = [page_status(0), page_notifications(1), page_files(2)]
    phone = phone_page()

    pad, gap = 36, 34
    canvas_w = pad * 2 + W * 3 + gap * 3 + phone.width
    canvas_h = max(H, phone.height) + pad * 2 + 46
    canvas = Image.new("RGB", (canvas_w, canvas_h), (0x07, 0x0A, 0x11))
    d = ImageDraw.Draw(canvas)

    label = f(17)
    top = pad + 40
    titles = ["① 实时状态", "② 通知中心", "③ 文件"]
    x = pad
    for i, page in enumerate(pages):
        mask = Image.new("L", (W, H), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, W - 1, H - 1], fill=255)
        canvas.paste(page, (x, top), mask)
        d.text((x + W / 2, top - 22), titles[i], font=label, fill=DIM, anchor="mm")
        x += W + gap

    canvas.paste(phone, (x, top))
    d.text((x + phone.width / 2, top - 22), "手机端", font=label, fill=DIM, anchor="mm")

    d.text((pad, canvas_h - 16),
           "示意图：手表按 466×466 圆屏 / 2x 密度绘制，非真机截图",
           font=f(13), fill=(0x5A, 0x67, 0x7D), anchor="ls")

    out = r"C:\Users\xiaochen\Documents\deepseek-harness\default-workspace\WatchLink\dist\watchlink-preview.png"
    canvas.save(out)
    print("saved", out, canvas.size)


main()
