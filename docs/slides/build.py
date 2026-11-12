#!/usr/bin/env python3
"""
Generates the static SVG charts used by the AQSS slide deck from live data.

The deck is plain HTML printed to PDF; charts are pre-rendered SVG so the PDF
looks identical whether or not a JavaScript engine runs.

Source: /tmp/aqss_data.json  (captured from the deployed backend)
"""
import json
import datetime
import shutil
import statistics
from pathlib import Path

HERE = Path(__file__).resolve().parent
DATA = Path("/tmp/aqss_data.json")

# palette — mirrors docs/slides/deck.css
CANVAS = "#0C1016"
LINE = "#2A323E"
MUTED = "#8A96A8"
TEXT = "#E8EDF4"
INFO = "#7DD3FC"
BAD = "#FB7185"
GOOD = "#4ADE80"
WARN = "#FBBF24"
AMBER = "#FCD34D"
VIOLET = "#C4B5FD"


def esc(s):
    return (s.replace("&", "&").replace("<", "<").replace(">", ">"))


# ── telemetry timeline ──────────────────────────────────────────────────────

def chart_telemetry(data, w=1090, h=300):
    tel = data["tel"]
    t0 = datetime.datetime.fromisoformat(tel[0]["recorded_at"].replace("Z", "+00:00"))
    t1 = datetime.datetime.fromisoformat(tel[-1]["recorded_at"].replace("Z", "+00:00"))
    span = (t1 - t0).total_seconds()

    # 5-minute medians for the trend; single samples above the median stay as
    # hairlines so one dust mote cannot be drawn as a trend (same rule the
    # dashboard's own chart follows).
    buckets = {}
    for x in tel:
        if x["pm25"] is None:
            continue
        t = datetime.datetime.fromisoformat(x["recorded_at"].replace("Z", "+00:00"))
        k = int((t - t0).total_seconds() // 300)
        buckets.setdefault(k, []).append(x)

    med, peaks, times = [], [], []
    for k in sorted(buckets):
        g = buckets[k]
        vals = [y["pm25"] for y in g]
        m = statistics.median(vals)
        t = t0 + datetime.timedelta(seconds=k * 300)
        times.append(t)
        med.append(m)
        peaks.append(max(vals))
        # a spike is a sample far above its own bucket median
        for y in g:
            if y["pm25"] > m * 1.6 and y["pm25"] > m + 12:
                peaks.append(y["pm25"])
    n = len(med)

    pl, pr, pt, pb = 44, 14, 16, 30
    iw, ih = w - pl - pr, h - pt - pb
    ymax = max(max(peaks) if peaks else 50, 380) * 1.12
    thr = 50.0

    def X(i):
        return pl + iw * (i / max(n - 1, 1))

    def Y(v):
        return pt + ih * (1 - v / ymax)

    def path(vals):
        return "M" + " L".join(f"{X(i):.1f},{Y(v):.1f}" for i, v in enumerate(vals))

    # gradient under the curve
    area = (path(med) + f" L{X(n-1):.1f},{pt+ih:.1f} L{X(0):.1f},{pt+ih:.1f} Z")

    out = [f'<svg viewBox="0 0 {w} {h}" width="100%" height="{h}" role="img">']
    out.append(
        f'<defs><linearGradient id="pmgrad" x1="0" y1="0" x2="0" y2="1">'
        f'<stop offset="0%" stop-color="{INFO}" stop-opacity=".26"/>'
        f'<stop offset="100%" stop-color="{INFO}" stop-opacity="0"/></linearGradient>'
        f'<linearGradient id="pmline" x1="0" y1="0" x2="1" y2="0">'
        f'<stop offset="0%" stop-color="{INFO}"/>'
        f'<stop offset="78%" stop-color="{WARN}"/>'
        f'<stop offset="100%" stop-color="{BAD}"/></linearGradient></defs>'
    )

    # y grid
    for v in [0, 100, 200, 300]:
        y = Y(v)
        out.append(f'<line x1="{pl}" y1="{y:.1f}" x2="{w-pr}" y2="{y:.1f}" stroke="{LINE}" stroke-width="1" opacity=".55"/>')
        out.append(f'<text x="{pl-9}" y="{y+3.5:.1f}" text-anchor="end" font-size="10" fill="{MUTED}" font-family="JetBrains Mono">{v}</text>')

    out.append(f'<text x="{pl-9}" y="{pt-4}" text-anchor="end" font-size="9.5" fill="{MUTED}">µg/m³</text>')

    # threshold band + line
    ythr = Y(thr)
    out.append(f'<rect x="{pl}" y="{pt}" width="{iw}" height="{ythr-pt:.1f}" fill="{GOOD}" opacity=".045"/>')
    out.append(f'<line x1="{pl}" y1="{ythr:.1f}" x2="{w-pr}" y2="{ythr:.1f}" stroke="{GOOD}" stroke-width="1.4" stroke-dasharray="6 4" opacity=".85"/>')
    out.append(f'<text x="{w-pr}" y="{ythr-6:.1f}" text-anchor="end" font-size="10" fill="{GOOD}" font-family="JetBrains Mono">ngưỡng 50 µg/m³</text>')

    # area + trend
    out.append(f'<path d="{area}" fill="url(#pmgrad)"/>')
    out.append(f'<path d="{path(med)}" fill="none" stroke="url(#pmline)" stroke-width="2" stroke-linejoin="round" stroke-linecap="round"/>')

    # activation event
    act = None
    for x in tel:
        t = datetime.datetime.fromisoformat(x["recorded_at"].replace("Z", "+00:00"))
        if t.hour == 17 and t.minute == 50 and t.second < 30:
            act = (t, x)
            break
    if act:
        t, x = act
        idx = int((t - t0).total_seconds() // 300)
        xa = X(min(idx, n - 1))
        out.append(f'<line x1="{xa:.1f}" y1="{pt}" x2="{xa:.1f}" y2="{pt+ih}" stroke="{BAD}" stroke-width="1.2" opacity=".8"/>')
        out.append(f'<circle cx="{xa:.1f}" cy="{Y(x["pm25"]):.1f}" r="4.5" fill="{BAD}" stroke="{CANVAS}" stroke-width="2"/>')
        lx = xa + 9
        out.append(f'<text x="{lx:.1f}" y="{pt+16}" font-size="11" font-weight="600" fill="{BAD}">17:50 — ngưỡng bị vượt, quạt BẬT level 3</text>')
        out.append(f'<text x="{lx:.1f}" y="{pt+31}" font-size="10.5" fill="{MUTED}">reason: threshold_breach · AUTO</text>')

    # decay annotation
    lastx = tel[-1]["pm25"]
    xend = X(n - 1)
    out.append(f'<text x="{xend-4:.1f}" y="{Y(lastx)-12:.1f}" text-anchor="end" font-size="11" font-weight="600" fill="{GOOD}">↓ còn {lastx:.0f} µg/m³</text>')

    # x axis
    for t, frac in [(times[0], 0), (times[len(times)//3], 1/3), (times[2*len(times)//3], 2/3), (times[-1], 1)]:
        x = pl + iw * frac
        out.append(f'<text x="{x:.1f}" y="{h-11}" text-anchor="middle" font-size="10" fill="{MUTED}" font-family="JetBrains Mono">{t.strftime("%H:%M")}</text>')
    out.append(f'<text x="{pl}" y="{h-11}" text-anchor="start" font-size="9.5" fill="#4E5A6B">UTC · 10/11/2026</text>')

    out.append("</svg>")
    return "\n".join(out)


# ── threshold factor breakdown ──────────────────────────────────────────────

def chart_threshold(data, w=560, h=280):
    previews = data["previews"]
    order = ["SP_201", "KHAC", "PHONG_NGU", "PHONG_TEST"]
    rows = []
    for code in order:
        p = previews.get(code)
        if not p:
            continue
        c = p["computed"]
        s = p["space"]
        rows.append({
            "code": code,
            "name": s["name"],
            "purpose": s["purpose"] or "—",
            "thr": c["threshold_pm25"],
            "dose": c["daily_dose_target"],
            "vol": c["volume_factor"],
            "vent": c["ventilation_factor"],
            "co2": c["co2_factor"],
            "occ": c["occupancy_hours"],
            "v": c["volume_m3"],
            "co2ppm": c["co2_ppm"],
        })

    out = [f'<svg viewBox="0 0 {w} {h}" width="100%" height="{h}" role="img">']
    out.append(
        f'<defs><linearGradient id="bar" x1="0" y1="0" x2="1" y2="0">'
        f'<stop offset="0%" stop-color="{INFO}"/>'
        f'<stop offset="100%" stop-color="{VIOLET}"/></linearGradient></defs>'
    )

    top, rowh = 26, 58
    for i, r in enumerate(rows):
        y = top + i * rowh
        out.append(f'<text x="0" y="{y}" font-size="12" font-weight="600" fill="{TEXT}">{esc(r["name"])}</text>')
        out.append(f'<text x="0" y="{y+14}" font-size="10" fill="{MUTED}">{esc(r["purpose"])} · {r["occ"]:g} h/ngày'
                   + (f' · {r["v"]:g} m³' if r["v"] else "") + "</text>")

        # factor chips as a multiplication chain
        fx, fy = 0, y + 24
        chain = [(f"{r['dose']:.1f}", "liều", INFO), ("×", "", MUTED),
                 (f"{r['vol']:.2f}", "V", AMBER), ("×", "", MUTED),
                 (f"{r['vent']:.2f}", "vent", WARN), ("×", "", MUTED),
                 (f"{r['co2']:.2f}", "CO₂", GOOD)]
        for val, lab, col in chain:
            if lab == "":
                out.append(f'<text x="{fx}" y="{fy+9}" font-size="12" fill="{MUTED}">{val}</text>')
                fx += 11
            else:
                out.append(f'<text x="{fx}" y="{fy+2}" font-size="9.5" fill="{MUTED}">{lab}</text>')
                out.append(f'<text x="{fx}" y="{fy+14}" font-size="12" font-family="JetBrains Mono" fill="{col}">{val}</text>')
                fx += max(26, len(val) * 8 + 12)

        # result bar
        barw = min(r["thr"] / 60 * 210, 210)
        out.append(f'<rect x="{w-218}" y="{y+2}" width="210" height="15" rx="4" fill="{LINE}" opacity=".45"/>')
        out.append(f'<rect x="{w-218}" y="{y+2}" width="{barw:.1f}" height="15" rx="4" fill="url(#bar)"/>')
        out.append(f'<text x="{w-218}" y="{y+30}" font-size="9.5" fill="{MUTED}">ngưỡng</text>')
        out.append(f'<text x="{w-4}" y="{y+15}" text-anchor="end" font-size="15" font-family="JetBrains Mono" font-weight="500" fill="{TEXT}">{r["thr"]:.1f}</text>')

    out.append("</svg>")
    return "\n".join(out)


# ── CO₂ → threshold response ────────────────────────────────────────────────

def chart_co2_response(w=520, h=210):
    """f(CO₂) as a function of live CO₂ — why the sensor is on the node."""
    out = [f'<svg viewBox="0 0 {w} {h}" width="100%" height="{h}" role="img">']
    pl, pr, pt, pb = 40, 14, 14, 30
    iw, ih = w - pl - pr, h - pt - pb
    lo, hi = 400.0, 1400.0
    ylo, yhi = 0.74, 1.0

    def X(v):
        return pl + iw * (v - lo) / (hi - lo)

    def Y(v):
        return pt + ih * (1 - (v - ylo) / (yhi - ylo))

    for v in [0.75, 0.85, 1.0]:
        y = Y(v)
        out.append(f'<line x1="{pl}" y1="{y:.1f}" x2="{w-pr}" y2="{y:.1f}" stroke="{LINE}" stroke-width="1" opacity=".55"/>')
        out.append(f'<text x="{pl-8}" y="{y+3.5:.1f}" text-anchor="end" font-size="9.5" fill="{MUTED}" font-family="JetBrains Mono">{v:.2f}</text>')

    pts = []
    for ppm in range(int(lo), int(hi) + 1, 10):
        delta = max(0.0, ppm - 420.0)
        sealed = min(1.0, delta / 800.0)
        f = 1.0 - 0.25 * sealed
        pts.append(f"{X(ppm):.1f},{Y(f):.1f}")
    out.append(f'<polyline points="{" ".join(pts)}" fill="none" stroke="{GOOD}" stroke-width="2.2" stroke-linejoin="round"/>')

    # live point at 815.6 ppm → 0.90
    live = 815.6
    f = 1.0 - 0.25 * min(1.0, (live - 420.0) / 800.0)
    out.append(f'<circle cx="{X(live):.1f}" cy="{Y(f):.1f}" r="5" fill="{GOOD}" stroke="{CANVAS}" stroke-width="2"/>')
    out.append(f'<text x="{X(live)+11:.1f}" y="{Y(f)+4:.1f}" font-size="11" font-weight="600" fill="{GOOD}">node đang đo: {live:.0f} ppm → {f:.2f}</text>')

    for v in [400, 800, 1200, 1400]:
        out.append(f'<text x="{X(v):.1f}" y="{h-10}" text-anchor="middle" font-size="9.5" fill="{MUTED}" font-family="JetBrains Mono">{v}</text>')
    out.append(f'<text x="{pl}" y="{h-10}" text-anchor="start" font-size="9.5" fill="#4E5A6B">CO₂ ppm</text>')
    out.append(f'<text x="{pl-8}" y="{pt+2}" text-anchor="end" font-size="9.5" fill="{MUTED}">f</text>')
    out.append("</svg>")
    return "\n".join(out)


def main():
    tpl = (HERE / "deck.html").read_text()

    # Static slides build without an old telemetry snapshot in /tmp.
    if "<!--CHART_TELEMETRY-->" in tpl or "<!--CHART_THRESHOLD-->" in tpl:
        data = json.loads(DATA.read_text())
        if "<!--CHART_TELEMETRY-->" in tpl:
            tpl = tpl.replace("<!--CHART_TELEMETRY-->", chart_telemetry(data))
        if "<!--CHART_THRESHOLD-->" in tpl:
            tpl = tpl.replace("<!--CHART_THRESHOLD-->", chart_threshold(data))
    if "<!--CHART_CO2-->" in tpl:
        tpl = tpl.replace("<!--CHART_CO2-->", chart_co2_response())

    out = HERE / "dist" / "Air-Quality-Supervision-System-bao-cao.html"
    out.parent.mkdir(exist_ok=True)
    out.write_text(tpl)

    # the deck is self-contained in dist/ so it can be opened or printed from anywhere
    (out.parent / "deck.css").write_text((HERE / "deck.css").read_text())
    if (out.parent / "fonts").exists():
        shutil.rmtree(out.parent / "fonts")
    shutil.copytree(HERE / "fonts", out.parent / "fonts")
    if (HERE / "img").exists():
        if (out.parent / "img").exists():
            shutil.rmtree(out.parent / "img")
        shutil.copytree(HERE / "img", out.parent / "img")

    print("wrote", out, len(tpl), "bytes")


if __name__ == "__main__":
    main()
