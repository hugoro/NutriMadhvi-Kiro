package com.example.data.export

import com.example.ui.tr

/**
 * Builds the self-contained HTML for the "Partilhar report" (detailed) share
 * on the Análise tab. Pure string assembly — NO Compose, Context, or coroutine
 * dependencies, so it is trivially unit-testable and can never touch the live
 * composition. The only dependency is [com.example.ui.tr] for i18n.
 *
 * 2026-07-20 — charts are now INTERACTIVE inline SVG+JS instead of static
 * base64 PNGs. The builder receives structured chart DATA (numbers + hex
 * colours + already-translated labels), embeds it as JSON on `<div data-chart>`
 * nodes, and a single inline `<script>` ([CHART_JS]) redraws each chart as SVG
 * and wires tap-to-select (identical behaviour to the on-screen app):
 *   - Composição: tapping a point retargets the KPI tiles to that instant
 *     (value@point + delta to latest, coloured by deltaTone) and draws a
 *     selection column; tap again / tap empty resets to the latest+window
 *     summary.
 *   - Evolutions (meals/activity stacked bars): tapping a day shows that day's
 *     value in a floating readout.
 * The file stays a single standalone `.html` — opens offline in any browser,
 * zero network dependencies. `<noscript>` tells the reader to open it in a
 * browser if the host sandboxes JS.
 */
object ReportHtmlBuilder {

    // ── Chart data models (serialized to JSON on the page) ──────────────────

    data class Slice(val label: String, val value: Double, val colorHex: String)
    data class PieData(val slices: List<Slice>, val unit: String)

    data class Seg(val value: Double, val colorHex: String)
    data class DayBar(val label: String, val segments: List<Seg>, val target: Double?)
    data class BarsData(val days: List<DayBar>)

    data class LineData(
        val label: String,
        val unit: String,
        val colorHex: String,
        val values: List<Double?>,
        val latest: Double?,
        val delta: Double?,
        val deltaTone: String,
        /** Unit for the KPI tile when it differs from the plotted [unit]
         *  (muscle/water plot kg but report % + pp delta). Null → uses [unit]. */
        val kpiUnit: String? = null,
        /** Alternate series the KPI reads instead of [values] (the % series for
         *  muscle/water). Same length/null-alignment as [values]. Null → uses [values]. */
        val kpiValues: List<Double?>? = null,
    )
    data class MultiData(val lines: List<LineData>, val xLabels: List<String>)

    /** All chart payloads for the report. Any may be null → chart omitted. */
    data class Charts(
        val macroPie: PieData? = null,
        val activityPie: PieData? = null,
        val mealsEvolution: BarsData? = null,
        val activityEvolution: BarsData? = null,
        val bodyComp: MultiData? = null,
    )

    /** One body-composition row (label + value + dot colour, mirrors the app). */
    data class BodyCompRow(val label: String, val value: String, val dotColor: String)

    /**
     * A generic detail-table row for the Alimentação / Atividade cards.
     * `highlighted` gives the lead row the violet wash; `indented` marks a
     * per-type / sub breakdown row; `dotColor` is the row's dot colour.
     */
    data class DetailRow(
        val label: String,
        val value: String,
        val dotColor: String,
        val highlighted: Boolean = false,
        val indented: Boolean = false,
    )

    fun build(
        userName: String,
        lang: String,
        timescale: String,
        rangeLabel: String,
        // energy
        consumedCalories: Int,
        activityKcal: Int,
        bmrKcal: Int,
        netCalories: Int,
        // Alimentação detail table (mirrors NutritionTableBody)
        foodRows: List<DetailRow>,
        // Atividade detail table (mirrors ActivitySummaryTableBody)
        activityRows: List<DetailRow>,
        // body comp
        bodyCompRows: List<BodyCompRow>,
        // charts (interactive SVG+JS)
        charts: Charts,
        generatedOn: String,
    ): String {
        val name = userName.takeIf { it.isNotBlank() }
        val title = run {
            val base = tr("Analytics", lang)
            if (name != null) "$base ${tr("of", lang)} ${esc(name)}" else base
        }
        // Labels the JS layer needs (it has no tr()).
        val jsLabels = mapOf(
            "latest" to tr("Latest", lang),
            "day" to tr("Day", lang),
        )
        val sb = StringBuilder(8192)
        sb.append("<!doctype html><html lang=\"").append(if (lang == "gu") "gu" else "pt").append("\"><head>")
        sb.append("<meta charset=\"utf-8\">")
        sb.append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
        sb.append("<title>").append(title).append("</title>")
        sb.append("<style>").append(CSS).append("</style>")
        sb.append("</head><body>")

        sb.append("<noscript><div class=\"noscript\">")
            .append(esc(tr("Open this file in a web browser to see the interactive charts.", lang)))
            .append("</div></noscript>")

        // ── Header ────────────────────────────────────────────────────────
        sb.append("<div class=\"header\">")
        sb.append("<h1>").append(title).append("</h1>")
        sb.append("<div class=\"subtitle\">").append(tr("Nutrition and activity status", lang)).append("</div>")
        sb.append("<div class=\"muted range\">").append(esc(rangeLabel)).append("</div>")
        sb.append("</div>")

        // ── Pies row (Macronutrients + Activity) ───────────────────────────
        if (charts.macroPie != null || charts.activityPie != null) {
            sb.append("<div class=\"pies\">")
            pieBlock(sb, charts.macroPie, tr("Macronutrients", lang))
            pieBlock(sb, charts.activityPie, tr("Activity", lang))
            sb.append("</div>")
        }

        // ── Card: Resumo de Alimentação ────────────────────────────────────
        if (foodRows.isNotEmpty() || charts.mealsEvolution != null) {
            sb.append("<div class=\"card\">")
            sb.append("<div class=\"card-title\">").append(tr("Nutritional Metric Summary Table", lang)).append("</div>")
            detailTable(sb, foodRows)
            barsBlock(sb, charts.mealsEvolution, tr("Meals evolution", lang))
            sb.append("</div>")
        }

        // ── Card: Resumo de Atividade ──────────────────────────────────────
        if (activityRows.isNotEmpty() || charts.activityEvolution != null) {
            sb.append("<div class=\"card\">")
            sb.append("<div class=\"card-title\">").append(tr("reports.activity.summarySection", lang)).append("</div>")
            detailTable(sb, activityRows)
            barsBlock(sb, charts.activityEvolution, tr("Activity evolution", lang))
            sb.append("</div>")
        }

        // ── Card: Balanço ──────────────────────────────────────────────────
        sb.append("<div class=\"card\">")
        sb.append("<div class=\"card-title\">").append(tr("Overall Summary", lang)).append("</div>")
        sb.append("<div class=\"section-title\">").append(tr("Energy", lang)).append("</div>")
        sb.append("<table>")
        energyRow(sb, tr("Consumed", lang), "$consumedCalories kcal", "#22C55E")
        energyRow(sb, tr("Spent — Activity", lang), "-$activityKcal kcal", "#EF4444")
        energyRow(sb, tr("Spent — BMR", lang), "-$bmrKcal kcal", "#3B82F6")
        run {
            val word = when {
                netCalories > 0 -> tr("surplus", lang)
                netCalories < 0 -> tr("deficit", lang)
                else -> tr("balanced", lang)
            }
            val signed = if (netCalories > 0) "+$netCalories" else "$netCalories"
            sb.append("<tr class=\"highlight\">")
            sb.append("<td><span class=\"dot\" style=\"background:#8B5CF6\"></span>")
                .append(tr("Net Energy Balance", lang)).append("</td>")
            sb.append("<td class=\"val\">").append(signed).append(" kcal · ").append(word).append("</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
        sb.append("</div>")

        // ── Card: Composição Corporal ──────────────────────────────────────
        if (bodyCompRows.isNotEmpty() || charts.bodyComp != null) {
            sb.append("<div class=\"card\">")
            sb.append("<div class=\"card-title\">").append(tr("Body Composition", lang)).append("</div>")
            if (bodyCompRows.isNotEmpty()) {
                sb.append("<table>")
                for (r in bodyCompRows) {
                    sb.append("<tr><td><span class=\"dot\" style=\"background:")
                        .append(r.dotColor).append("\"></span>").append(esc(r.label)).append("</td>")
                    sb.append("<td class=\"val\">").append(esc(r.value)).append("</td></tr>")
                }
                sb.append("</table>")
            }
            multiBlock(sb, charts.bodyComp)
            sb.append("</div>")
        }

        // ── Footer ─────────────────────────────────────────────────────────
        sb.append("<div class=\"footer muted small\">")
            .append(tr("Generated by NutriMadhvi", lang)).append(" · ").append(esc(generatedOn))
            .append("</div>")

        // ── JS labels + chart script ───────────────────────────────────────
        sb.append("<script>window.__L=").append(jsonObj(jsLabels)).append(";</script>")
        sb.append("<script>").append(CHART_JS).append("</script>")

        sb.append("</body></html>")
        return sb.toString()
    }

    // ── Chart emitters — a <div data-chart='...json...'> per chart ──────────

    private fun pieBlock(sb: StringBuilder, data: PieData?, alt: String) {
        if (data == null) return
        val json = buildString {
            append("{\"type\":\"pie\",\"unit\":").append(jsonStr(data.unit)).append(",\"slices\":[")
            data.slices.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append("{\"label\":").append(jsonStr(s.label))
                    .append(",\"value\":").append(num(s.value))
                    .append(",\"color\":").append(jsonStr(s.colorHex)).append('}')
            }
            append("]}")
        }
        sb.append("<div class=\"chart pie\" aria-label=\"").append(esc(alt))
            .append("\" data-chart='").append(attr(json)).append("'></div>")
    }

    private fun barsBlock(sb: StringBuilder, data: BarsData?, alt: String) {
        if (data == null || data.days.isEmpty()) return
        val json = buildString {
            append("{\"type\":\"bars\",\"days\":[")
            data.days.forEachIndexed { i, d ->
                if (i > 0) append(',')
                append("{\"label\":").append(jsonStr(d.label)).append(",\"target\":")
                    .append(if (d.target != null) num(d.target) else "null").append(",\"segs\":[")
                d.segments.forEachIndexed { j, seg ->
                    if (j > 0) append(',')
                    append("{\"v\":").append(num(seg.value)).append(",\"c\":").append(jsonStr(seg.colorHex)).append('}')
                }
                append("]}")
            }
            append("]}")
        }
        sb.append("<div class=\"chart bars\" aria-label=\"").append(esc(alt))
            .append("\" data-chart='").append(attr(json)).append("'></div>")
    }

    private fun multiBlock(sb: StringBuilder, data: MultiData?) {
        if (data == null || data.lines.isEmpty()) return
        val json = buildString {
            append("{\"type\":\"multi\",\"x\":[")
            data.xLabels.forEachIndexed { i, x -> if (i > 0) append(','); append(jsonStr(x)) }
            append("],\"lines\":[")
            data.lines.forEachIndexed { i, ln ->
                if (i > 0) append(',')
                append("{\"label\":").append(jsonStr(ln.label))
                    .append(",\"unit\":").append(jsonStr(ln.unit))
                    .append(",\"kpiUnit\":").append(if (ln.kpiUnit != null) jsonStr(ln.kpiUnit) else "null")
                    .append(",\"color\":").append(jsonStr(ln.colorHex))
                    .append(",\"latest\":").append(if (ln.latest != null) num(ln.latest) else "null")
                    .append(",\"delta\":").append(if (ln.delta != null) num(ln.delta) else "null")
                    .append(",\"tone\":").append(jsonStr(ln.deltaTone))
                    .append(",\"values\":[")
                ln.values.forEachIndexed { j, v -> if (j > 0) append(','); append(if (v != null) num(v) else "null") }
                append("]")
                if (ln.kpiValues != null) {
                    append(",\"kpiValues\":[")
                    ln.kpiValues.forEachIndexed { j, v -> if (j > 0) append(','); append(if (v != null) num(v) else "null") }
                    append("]")
                }
                append("}")
            }
            append("]}")
        }
        sb.append("<div class=\"chart multi\" data-chart='").append(attr(json)).append("'></div>")
    }

    private fun detailTable(sb: StringBuilder, rows: List<DetailRow>) {
        if (rows.isEmpty()) return
        sb.append("<table>")
        for (r in rows) {
            val trClass = if (r.highlighted) " class=\"highlight\"" else ""
            val tdClass = if (r.indented) "class=\"indent\"" else ""
            sb.append("<tr").append(trClass).append(">")
            sb.append("<td ").append(tdClass).append("><span class=\"dot\" style=\"background:")
                .append(r.dotColor).append("\"></span>").append(esc(r.label)).append("</td>")
            sb.append("<td class=\"val\">").append(esc(r.value)).append("</td>")
            sb.append("</tr>")
        }
        sb.append("</table>")
    }

    private fun energyRow(sb: StringBuilder, label: String, value: String, dotColor: String) {
        sb.append("<tr>")
        sb.append("<td><span class=\"dot\" style=\"background:").append(dotColor).append("\"></span>")
            .append(label).append("</td>")
        sb.append("<td class=\"val\">").append(value).append("</td>")
        sb.append("</tr>")
    }

    // ── JSON / escaping helpers (no external libs) ──────────────────────────

    /** Drop trailing zeros: 84.0 → "84", 84.75 → "84.75". */
    private fun num(v: Double): String {
        if (!v.isFinite()) return "0"
        val r = Math.round(v * 100.0) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
    }

    private fun jsonStr(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '<' -> append("\\u003c")
            '>' -> append("\\u003e")
            '&' -> append("\\u0026")
            else -> append(c)
        }
        append('"')
    }

    private fun jsonObj(m: Map<String, String>): String = buildString {
        append('{')
        m.entries.forEachIndexed { i, (k, v) ->
            if (i > 0) append(',')
            append(jsonStr(k)).append(':').append(jsonStr(v))
        }
        append('}')
    }

    /** Escape a JSON string so it can live inside a single-quoted HTML attribute. */
    private fun attr(json: String): String = json.replace("'", "&#39;")

    private fun esc(s: String): String = buildString(s.length + 8) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(c)
        }
    }

    private const val CSS = """
:root{--bg:#0F172A;--card:#1E293B;--border:#334155;--muted:#94A3B8;--accent:#A78BFA}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:#fff;font-family:system-ui,-apple-system,Roboto,"Helvetica Neue",Arial,sans-serif;padding:16px;max-width:820px;margin-left:auto;margin-right:auto;line-height:1.4}
.header{margin-bottom:16px}
h1{font-size:22px;font-weight:700;margin:0 0 4px}
.subtitle{color:var(--accent);font-weight:600;font-size:13px}
.range{font-size:12px;margin-top:2px}
.muted{color:var(--muted)}
.small{font-size:12px}
.noscript{background:#7C3AED;color:#fff;padding:10px 14px;border-radius:10px;margin-bottom:16px;font-size:13px}
.card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:16px;margin-bottom:16px}
.card-title{color:#fff;font-weight:700;font-size:15px;margin:0 0 10px}
.section-title{color:var(--accent);font-size:11px;letter-spacing:1.5px;text-transform:uppercase;font-weight:600;margin:0 0 8px}
table{width:100%;border-collapse:collapse}
td{padding:6px 0;border-bottom:1px solid rgba(51,65,85,.5);font-size:14px;vertical-align:middle}
tr:last-child td{border-bottom:none}
td.val{text-align:right;font-weight:700;white-space:nowrap}
tr.highlight td{background:rgba(139,92,246,.08)}
td.indent{padding-left:14px}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;margin-right:8px;vertical-align:middle}
.pies{display:flex;gap:12px;margin-bottom:16px}
.pies .chart{flex:1;min-width:0}
.chart{width:100%;margin-top:6px}
.chart svg{width:100%;height:auto;display:block}
.kpis{display:flex;gap:8px;margin-bottom:10px}
.kpi{flex:1;background:#1E293B;border-radius:8px;padding:6px 8px;min-width:0}
.kpi .k-lab{display:flex;align-items:center;gap:4px;color:#CBD5E1;font-size:9px;font-weight:600}
.kpi .k-dot{width:6px;height:6px;border-radius:50%;display:inline-block}
.kpi .k-val{color:#fff;font-size:14px;font-weight:700;margin-top:2px}
.kpi .k-delta{font-size:9px;font-weight:600}
.readout{font-size:11px;color:#CBD5E1;min-height:14px;margin-top:4px}
.legend{display:flex;flex-wrap:wrap;gap:8px 14px;margin-top:8px;font-size:11px;color:#E2E8F0}
.legend .lg{display:flex;align-items:center;gap:6px}
.legend .lg-dot{width:8px;height:8px;border-radius:50%}
"""

    /**
     * Self-contained chart renderer + interaction. Reads every
     * `.chart[data-chart]`, draws an inline SVG, and wires tap-to-select.
     * No dependencies. Mirrors the app's colours, small-multiples layout,
     * verdict-tone delta colours, and reset-on-reclick behaviour.
     */
    private const val CHART_JS = """
(function(){
  var NS="http://www.w3.org/2000/svg";
  var L=window.__L||{latest:"Latest",day:"Day"};
  function el(n,a){var e=document.createElementNS(NS,n);if(a)for(var k in a)e.setAttribute(k,a[k]);return e;}
  function tone(t){return t==="good"?"#10B981":t==="bad"?"#EF4444":"#94A3B8";}
  function fmt(v){if(v==null)return "—";var r=Math.round(v*100)/100;return (r===Math.floor(r)?r.toFixed(0):(""+r));}
  function lastNonNull(a){for(var i=a.length-1;i>=0;i--)if(a[i]!=null)return a[i];return null;}
  function firstNonNull(a){for(var i=0;i<a.length;i++)if(a[i]!=null)return a[i];return null;}

  function pie(host,d){
    var W=260,H=150,cx=75,cy=75,rO=60,rI=37;
    var total=d.slices.reduce(function(s,x){return s+x.value;},0)||1;
    var svg=el("svg",{viewBox:"0 0 "+W+" "+H});
    var start=-90;
    d.slices.forEach(function(s){
      var sweep=s.value/total*360, a0=start*Math.PI/180, a1=(start+sweep)*Math.PI/180;
      var large=sweep>180?1:0;
      var x0=cx+rO*Math.cos(a0),y0=cy+rO*Math.sin(a0),x1=cx+rO*Math.cos(a1),y1=cy+rO*Math.sin(a1);
      var xi1=cx+rI*Math.cos(a1),yi1=cy+rI*Math.sin(a1),xi0=cx+rI*Math.cos(a0),yi0=cy+rI*Math.sin(a0);
      var path=["M",x0,y0,"A",rO,rO,0,large,1,x1,y1,"L",xi1,yi1,"A",rI,rI,0,large,0,xi0,yi0,"Z"].join(" ");
      svg.appendChild(el("path",{d:path,fill:s.color}));
      start+=sweep;
    });
    host.appendChild(svg);
    var lg=document.createElement("div");lg.className="legend";
    d.slices.forEach(function(s){
      var pct=Math.round(s.value/total*100);
      var row=document.createElement("span");row.className="lg";
      row.innerHTML='<span class="lg-dot" style="background:'+s.color+'"></span>'+s.label+' · '+fmt(s.value)+(d.unit?(" "+d.unit):"")+' ('+pct+'%)';
      lg.appendChild(row);
    });
    host.appendChild(lg);
  }

  function bars(host,d){
    var days=d.days, n=days.length;
    var W=Math.max(320,n*44), H=180, padL=8,padR=8,padT=10,footer=22;
    var plotW=W-padL-padR, plotH=H-padT-footer;
    var maxV=0;
    days.forEach(function(b){var s=b.segs.reduce(function(a,x){return a+x.v;},0);if(b.target)s=Math.max(s,b.target);if(s>maxV)maxV=s;});
    maxV=maxV||1;
    var svg=el("svg",{viewBox:"0 0 "+W+" "+H});
    var slot=plotW/n, bw=slot*0.6, gap=(slot-bw)/2;
    var readout=document.createElement("div");readout.className="readout";
    function clear(){Array.prototype.forEach.call(svg.querySelectorAll(".sel"),function(e){e.setAttribute("opacity","0");});readout.textContent="";}
    days.forEach(function(b,i){
      var x=padL+i*slot+gap, yAcc=padT+plotH, tot=0;
      b.segs.forEach(function(sg){
        var h=sg.v/maxV*plotH; yAcc-=h; tot+=sg.v;
        svg.appendChild(el("rect",{x:x,y:yAcc,width:bw,height:Math.max(0,h),fill:sg.c,rx:2}));
      });
      if(b.target!=null){var ty=padT+plotH-(b.target/maxV*plotH);
        svg.appendChild(el("line",{x1:x,y1:ty,x2:x+bw,y2:ty,stroke:"#F59E0B","stroke-width":1.5,"stroke-dasharray":"4 3"}));}
      var hit=el("rect",{x:padL+i*slot,y:padT,width:slot,height:plotH,fill:"transparent",cursor:"pointer"});
      var sel=el("rect",{x:padL+i*slot,y:padT,width:slot,height:plotH,fill:"#38BDF8","fill-opacity":0.08,opacity:0,"class":"sel"});
      svg.appendChild(sel);svg.appendChild(hit);
      hit.addEventListener("click",function(ev){ev.stopPropagation();
        var on=sel.getAttribute("opacity")==="1";clear();
        if(!on){sel.setAttribute("opacity","1");readout.textContent=(L.day||"Day")+" "+b.label+": "+fmt(tot)+" kcal";}
      });
      var lab=document.createElementNS(NS,"text");lab.setAttribute("x",x+bw/2);lab.setAttribute("y",padT+plotH+14);
      lab.setAttribute("fill","#64748B");lab.setAttribute("font-size","9");lab.setAttribute("text-anchor","middle");lab.textContent=b.label;
      svg.appendChild(lab);
    });
    svg.addEventListener("click",clear);
    host.appendChild(svg);host.appendChild(readout);
  }

  function multi(host,d){
    var lines=d.lines, x=d.x, nX=x.length, nL=lines.length;
    // per-line padded range
    var ranges=lines.map(function(ln){
      var vs=ln.values.filter(function(v){return v!=null;});
      if(!vs.length)return {min:0,max:1};
      var mn=Math.min.apply(null,vs),mx=Math.max.apply(null,vs),raw=mx-mn;
      var floor=(ln.unit==="kg")?1.5:(ln.unit==="%"?2:Math.max(mx*0.05,0.01));
      var span=Math.max(raw,floor),pad=span*0.2,exp=(span-raw)/2;
      return {min:mn-exp-pad,max:mx+exp+pad};
    });
    var gutter=44,padR=8,padT=6,footer=22,bandH=72,W=Math.max(320,gutter+padR+nX*40),plotW=W-gutter-padR;
    var H=padT+bandH*nL+footer;
    var svg=el("svg",{viewBox:"0 0 "+W+" "+H});
    function xAt(i){return nX<=1?gutter+plotW/2:gutter+plotW*i/(nX-1);}
    // KPI tiles
    var kpis=document.createElement("div");kpis.className="kpis";
    var tiles=lines.map(function(ln){
      var t=document.createElement("div");t.className="kpi";
      t.innerHTML='<div class="k-lab"><span class="k-dot" style="background:'+ln.color+'"></span>'+ln.label+'</div>'+
        '<div class="k-val"></div><div class="k-delta"></div>';
      kpis.appendChild(t);return t;
    });
    function renderKpi(sel){
      lines.forEach(function(ln,li){
        var t=tiles[li], v, delta, tn=ln.tone;
        // KPI may report a different quantity than the plotted line (muscle/water
        // plot kg but KPI shows % + pp delta); use kpiValues/kpiUnit when present.
        var kv=ln.kpiValues||ln.values, ku=ln.kpiUnit||ln.unit;
        if(sel==null){v=(ln.kpiValues?lastNonNull(kv):ln.latest);delta=ln.delta;}
        else{v=kv[sel];if(v==null){v=(ln.kpiValues?lastNonNull(kv):ln.latest);delta=ln.delta;}else{var lst=lastNonNull(kv);delta=(lst!=null)?(lst-v):null;}}
        t.querySelector(".k-val").textContent=(v==null?"—":fmt(v)+(ku?(" "+ku):""));
        var dd=t.querySelector(".k-delta");
        if(delta==null){dd.textContent="—";dd.style.color="#94A3B8";}
        else{var sign=delta>0?"▲":(delta<0?"▼":"=");dd.textContent=sign+" "+fmt(Math.abs(delta))+(ku||"");dd.style.color=tone(tn);}
      });
    }
    host.appendChild(kpis);
    var selCol=el("rect",{y:padT,width:14,height:bandH*nL,fill:"#38BDF8","fill-opacity":0.08,opacity:0});
    var selLine=el("line",{y1:padT,y2:padT+bandH*nL,stroke:"#38BDF8","stroke-opacity":0.55,"stroke-width":1.5,opacity:0});
    svg.appendChild(selCol);svg.appendChild(selLine);
    var dots=[];
    lines.forEach(function(ln,li){
      var rg=ranges[li],span=(rg.max-rg.min)||1,bTop=padT+bandH*li,pTop=bTop+4,pBot=bTop+bandH-4,pH=pBot-pTop;
      function yAt(v){var yn=Math.min(1,Math.max(0,(v-rg.min)/span));return pTop+pH*(1-yn);}
      if(li>0)svg.appendChild(el("line",{x1:gutter,y1:bTop,x2:gutter+plotW,y2:bTop,stroke:"#334155","stroke-opacity":0.35,"stroke-width":1}));
      svg.appendChild(el("line",{x1:gutter,y1:bTop+bandH/2,x2:gutter+plotW,y2:bTop+bandH/2,stroke:"#1E293B","stroke-width":1}));
      // gutter labels (max@top, min@bottom)
      var tmax=document.createElementNS(NS,"text");tmax.setAttribute("x",gutter-4);tmax.setAttribute("y",bTop+10);tmax.setAttribute("fill","#64748B");tmax.setAttribute("font-size","9");tmax.setAttribute("text-anchor","end");tmax.textContent=fmt(rg.max);svg.appendChild(tmax);
      var tmin=document.createElementNS(NS,"text");tmin.setAttribute("x",gutter-4);tmin.setAttribute("y",bTop+bandH-4);tmin.setAttribute("fill","#64748B");tmin.setAttribute("font-size","9");tmin.setAttribute("text-anchor","end");tmin.textContent=fmt(rg.min);svg.appendChild(tmin);
      // line (bridge nulls)
      var prev=null;
      ln.values.forEach(function(v,i){if(v==null)return;var cx=xAt(i),cy=yAt(v);
        if(prev)svg.appendChild(el("line",{x1:prev[0],y1:prev[1],x2:cx,y2:cy,stroke:ln.color,"stroke-width":2,"stroke-linecap":"round"}));
        prev=[cx,cy];
      });
      ln.values.forEach(function(v,i){if(v==null)return;var cx=xAt(i),cy=yAt(v);
        var c=el("circle",{cx:cx,cy:cy,r:2.5,fill:ln.color});svg.appendChild(c);dots.push({i:i,el:c,cx:cx,cy:cy,color:ln.color});
      });
    });
    // x labels + hit columns
    var every=Math.max(1,Math.round(48/(plotW/Math.max(1,nX-1))));
    x.forEach(function(lab,i){
      if(i%every!==0 && i!==nX-1)return;
      var t=document.createElementNS(NS,"text");t.setAttribute("x",xAt(i));t.setAttribute("y",padT+bandH*nL+14);
      t.setAttribute("fill","#64748B");t.setAttribute("font-size","9");t.setAttribute("text-anchor","middle");t.textContent=lab;
      t.setAttribute("data-xi",i);svg.appendChild(t);
    });
    var sel=null;
    function apply(){
      if(sel==null){selCol.setAttribute("opacity","0");selLine.setAttribute("opacity","0");}
      else{var sx=xAt(sel);selCol.setAttribute("x",sx-7);selCol.setAttribute("opacity","1");
        selLine.setAttribute("x1",sx);selLine.setAttribute("x2",sx);selLine.setAttribute("opacity","1");}
      dots.forEach(function(dt){var on=(sel!=null&&dt.i===sel);dt.el.setAttribute("r",on?4.5:2.5);
        dt.el.setAttribute("stroke",on?"#fff":"none");dt.el.setAttribute("stroke-width",on?1.5:0);});
      renderKpi(sel);
    }
    for(var i=0;i<nX;i++){(function(i){
      var hit=el("rect",{x:xAt(i)-plotW/(2*Math.max(1,nX-1)),y:padT,width:Math.max(12,plotW/Math.max(1,nX-1)),height:bandH*nL,fill:"transparent",cursor:"pointer"});
      hit.addEventListener("click",function(ev){ev.stopPropagation();sel=(sel===i)?null:i;apply();});
      svg.appendChild(hit);
    })(i);}
    svg.addEventListener("click",function(){sel=null;apply();});
    host.appendChild(svg);
    apply();
  }

  document.querySelectorAll(".chart[data-chart]").forEach(function(host){
    var d;try{d=JSON.parse(host.getAttribute("data-chart"));}catch(e){return;}
    if(d.type==="pie")pie(host,d);
    else if(d.type==="bars")bars(host,d);
    else if(d.type==="multi")multi(host,d);
  });
})();
"""
}
