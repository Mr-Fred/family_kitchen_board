package com.familykitchen.board.data

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.familykitchen.board.domain.ExportCheckboxState
import com.familykitchen.board.domain.ExportLineItem
import com.familykitchen.board.domain.ExportMetricChip
import com.familykitchen.board.domain.ExportSection
import com.familykitchen.board.domain.ExportedKitchenDocument
import com.familykitchen.board.domain.KitchenBoardException
import com.familykitchen.board.domain.ValidationException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

// Deterministic multi-page A4 layout planner (100% pure Kotlin math) and Android PdfDocument
// renderer styled in SavorAI's Warm Culinary Linen, Herbal Sage, and Roasted Terracotta palette.

// Standard ISO-216 A4 portrait dimensions in PostScript points (1 pt = 1/72 inch).
private const val DEFAULT_A4_WIDTH_PT: Int = 595
private const val DEFAULT_A4_HEIGHT_PT: Int = 842

// Page margins, header heights, and footer reservations shared between pure math and Canvas draw.
private const val PAGE_MARGIN_X_PT: Float = 36f
private const val PAGE_TOP_MARGIN_PT: Float = 28f
private const val FIRST_PAGE_HEADER_WITH_METRICS_PT: Float = 128f
private const val FIRST_PAGE_HEADER_COMPACT_PT: Float = 82f
private const val CONTINUATION_HEADER_PT: Float = 28f
private const val FOOTER_RESERVED_PT: Float = 44f
private const val SECTION_TOP_SPACING_PT: Float = 12f
private const val ITEM_TOP_SPACING_PT: Float = 4f

// Warm Culinary SavorAI ARGB color constants (zero cold blues, zero clinical purples).
private const val COLOR_PAGE_WHITE: Int = 0xFFFFFFFF.toInt()
private const val COLOR_WARM_LINEN: Int = 0xFFFAF7F2.toInt()
private const val COLOR_HERBAL_SAGE: Int = 0xFF2E7D5A.toInt()
private const val COLOR_SAGE_PILL_BG: Int = 0xFFEAF4EE.toInt()
private const val COLOR_URGENT_TINT: Int = 0xFFFBF6EE.toInt()
private const val COLOR_TERRACOTTA: Int = 0xFFC85A32.toInt()
private const val COLOR_DIVIDER_SUBTLE: Int = 0xFFE2DDD5.toInt()
private const val COLOR_CHARCOAL_INK: Int = 0xFF1C1917.toInt()
private const val COLOR_SLATE_MUTED: Int = 0xFF57534E.toInt()
private const val COLOR_ASH_FOOTER: Int = 0xFF78716C.toInt()

/** Classification of a planned vertical row on a paginated SavorAI PDF page. */
enum class PdfRowKind {
  // Bold aisle, urgency, or kitchen-zone divider that must never appear orphaned at page bottom.
  /** Section title row paired atomically with at least its first [LINE_ITEM]. */
  SECTION_HEADER,

  // Individual grocery item, custom note, or pantry stock row.
  /** Checkable or bulleted content row with optional wrapped subtitle and right-aligned badge. */
  LINE_ITEM,
}

/**
 * Pure, pre-measured PDF row emitted by [KitchenPdfExporter.planPdfPages] before touching Android
 * `Canvas` APIs so pagination and word wrapping remain 100% testable on a headless JVM.
 *
 * @property kind Row structural category ([PdfRowKind.SECTION_HEADER] or [PdfRowKind.LINE_ITEM]).
 * @property primaryLines Wrapped primary label lines guaranteed to fit horizontal column width.
 * @property secondaryLines Optional wrapped subtitle lines (meal usage, rescue meal, or note).
 * @property trailingBadge Right-aligned package/price, urgency, or section count summary.
 * @property checkboxState Checkbox drawing mode (`NONE`, `UNCHECKED`, or `CHECKED`).
 * @property isUrgentHighlight `true` when the row renders with SavorAI Terracotta urgency tinting.
 * @property topSpacingPt Vertical gap in points inserted immediately above this row.
 * @property heightPt Total vertical height in points consumed by this row's card/text block.
 */
data class PlannedPdfRow(
  val kind: PdfRowKind,
  val primaryLines: List<String>,
  val secondaryLines: List<String> = emptyList(),
  val trailingBadge: String = "",
  val checkboxState: ExportCheckboxState = ExportCheckboxState.NONE,
  val isUrgentHighlight: Boolean = false,
  val topSpacingPt: Float = 0f,
  val heightPt: Float = 20f,
)

/**
 * Immutable layout plan for a single A4 page produced by [KitchenPdfExporter.planPdfPages].
 *
 * @property pageNumber 1-based page index (`1..totalPages`).
 * @property totalPages Total number of pages in the paginated document (`>= 1`).
 * @property includesDocumentHeader `true` on page 1 where the Warm Linen banner and metrics render.
 * @property rows Ordered list of [PlannedPdfRow] elements placed on this page.
 */
data class PlannedPdfPage(
  val pageNumber: Int,
  val totalPages: Int,
  val includesDocumentHeader: Boolean,
  val rows: List<PlannedPdfRow>,
)

/**
 * Multi-page A4 layout planner and native Android [PdfDocument] stream writer for SavorAI
 * Grocery Lists and Pantry Inventories (`0` storage permissions required when paired with SAF).
 */
object KitchenPdfExporter {

  /**
   * Greedy pure-Kotlin word wrapper splitting [text] into lines of at most [maxCharsPerLine]
   * characters, hard-slicing unbroken tokens that exceed the character budget.
   *
   * @param text Input string (may contain spaces or explicit `\n` line breaks).
   * @param maxCharsPerLine Maximum characters permitted per wrapped line (`>= 1`).
   * @return Ordered list of non-blank wrapped lines (`emptyList()` when [text] is blank).
   */
  fun wrapTextToCharBudget(text: String, maxCharsPerLine: Int): List<String> {
    // Clamp character budget at >= 1 so tiny caller budgets never divide by zero or loop forever.
    val budget = maxCharsPerLine.coerceAtLeast(1)
    if (text.isBlank()) return emptyList()

    val wrapped = mutableListOf<String>()
    // Preserve explicit paragraph breaks while collapsing redundant intra-line whitespace.
    for (paragraph in text.split('\n')) {
      val words = paragraph.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
      if (words.isEmpty()) continue

      val currentLine = StringBuilder()
      for (word in words) {
        when {
          // Hard-slice pathological unbroken tokens so every output line strictly obeys budget.
          word.length > budget -> {
            if (currentLine.isNotEmpty()) {
              wrapped.add(currentLine.toString())
              currentLine.clear()
            }
            val chunks = word.chunked(budget)
            for (idx in 0 until chunks.lastIndex) wrapped.add(chunks[idx])
            currentLine.append(chunks.last())
          }

          currentLine.isEmpty() -> currentLine.append(word)
          currentLine.length + 1 + word.length <= budget -> currentLine.append(' ').append(word)
          else -> {
            wrapped.add(currentLine.toString())
            currentLine.clear()
            currentLine.append(word)
          }
        }
      }
      if (currentLine.isNotEmpty()) wrapped.add(currentLine.toString())
    }
    return wrapped
  }

  /**
   * Paginates [document] into 1-based [PlannedPdfPage]s using 100% pure Kotlin math (zero
   * `android.graphics.*` calls), enforcing word-wrap budgets and **orphan-header prevention** so a
   * [PdfRowKind.SECTION_HEADER] never lands at the bottom of a page without its first line item.
   *
   * @param document Formatted [ExportedKitchenDocument] from `KitchenExportFormatter`.
   * @param pageWidthPt Target page width in PostScript points (`595` pt A4 default).
   * @param pageHeightPt Target page height in PostScript points (`842` pt A4 default).
   * @return Non-empty list of [PlannedPdfPage]s stamped with accurate `Page X of Y` totals.
   */
  fun planPdfPages(
    document: ExportedKitchenDocument,
    pageWidthPt: Int = DEFAULT_A4_WIDTH_PT,
    pageHeightPt: Int = DEFAULT_A4_HEIGHT_PT,
  ): List<PlannedPdfPage> {
    // Enforce minimum sane page bounds so custom test page dimensions paginate deterministically.
    val safeWidthPt = pageWidthPt.coerceAtLeast(240).toFloat()
    val safeHeightPt = pageHeightPt.coerceAtLeast(260).toFloat()
    val contentWidthPt = (safeWidthPt - PAGE_MARGIN_X_PT * 2f).coerceAtLeast(140f)

    // Compute vertical body height budgets for Page 1 (full header) vs Pages 2..N (compact header).
    val firstHeaderPt =
      if (document.metrics.isNotEmpty()) FIRST_PAGE_HEADER_WITH_METRICS_PT else FIRST_PAGE_HEADER_COMPACT_PT
    val firstPageBudgetPt =
      (safeHeightPt - PAGE_TOP_MARGIN_PT - firstHeaderPt - FOOTER_RESERVED_PT).coerceAtLeast(72f)
    val contPageBudgetPt =
      (safeHeightPt - PAGE_TOP_MARGIN_PT - CONTINUATION_HEADER_PT - FOOTER_RESERVED_PT).coerceAtLeast(72f)

    // Derive horizontal character wrapping budgets proportionally from usable contentWidthPt.
    val sectionChars = ((contentWidthPt - 88f) / 6.2f).toInt().coerceIn(16, 74)
    val badgedChars = ((contentWidthPt - 188f) / 5.6f).toInt().coerceIn(14, 60)
    val wideChars = ((contentWidthPt - 36f) / 5.6f).toInt().coerceIn(20, 84)
    val subChars = ((contentWidthPt - 48f) / 4.8f).toInt().coerceIn(18, 92)

    val rawPages = mutableListOf<List<PlannedPdfRow>>()
    val currentRows = mutableListOf<PlannedPdfRow>()
    var usedPt = 0f
    var isFirstPage = true

    fun budgetPt(): Float = if (isFirstPage) firstPageBudgetPt else contPageBudgetPt
    fun flushPage() {
      rawPages.add(currentRows.toList())
      currentRows.clear()
      usedPt = 0f
      isFirstPage = false
    }

    for (section in document.sections) {
      val headerRow = buildSectionHeaderRow(section, sectionChars)
      // Synthesize a placeholder item when a section list is empty so headers are never orphaned.
      val sourceItems = section.items.ifEmpty {
        listOf(ExportLineItem(primaryText = "No items recorded in this section"))
      }
      val itemRows = sourceItems.map { buildLineItemRow(it, badgedChars, wideChars, subChars) }
      val firstItem = itemRows.first()

      // Orphan-header prevention: always place SECTION_HEADER + its first LINE_ITEM atomically.
      val pairPt = headerRow.topSpacingPt + headerRow.heightPt + firstItem.topSpacingPt + firstItem.heightPt
      if (currentRows.isNotEmpty() && usedPt + pairPt > budgetPt()) {
        flushPage()
      }
      val placedHeader = if (currentRows.isEmpty()) headerRow.copy(topSpacingPt = 0f) else headerRow
      currentRows.add(placedHeader)
      currentRows.add(firstItem)
      usedPt += placedHeader.topSpacingPt + placedHeader.heightPt + firstItem.topSpacingPt + firstItem.heightPt

      // Flow remaining items onto continuation pages whenever vertical height budget is reached.
      for (itemRow in itemRows.drop(1)) {
        val neededPt = itemRow.topSpacingPt + itemRow.heightPt
        if (currentRows.isNotEmpty() && usedPt + neededPt > budgetPt()) flushPage()
        val placedItem = if (currentRows.isEmpty()) itemRow.copy(topSpacingPt = 0f) else itemRow
        currentRows.add(placedItem)
        usedPt += placedItem.topSpacingPt + placedItem.heightPt
      }
    }

    // Ensure even a completely empty document produces at least 1 valid PDF page.
    if (currentRows.isNotEmpty() || rawPages.isEmpty()) rawPages.add(currentRows.toList())
    val totalPages = rawPages.size
    return rawPages.mapIndexed { idx, rows -> PlannedPdfPage(idx + 1, totalPages, idx == 0, rows) }
  }

  /**
   * Renders [document] into multi-page A4 (`595 x 842` pt) PDF bytes via Android's native
   * [PdfDocument] engine on [ioDispatcher] and flushes them to [outputStream].
   *
   * Deterministically finishes any open page and closes [PdfDocument] inside `finally` so native
   * Skia PDF buffers never leak on I/O errors or coroutine cancellation.
   *
   * @param document Structured [ExportedKitchenDocument] to render.
   * @param outputStream Destination stream (for example, `ContentResolver.openOutputStream(uri, "w")`).
   * @param ioDispatcher Background dispatcher executing PDF drawing and stream I/O.
   */
  suspend fun writeKitchenPdfToStream(
    document: ExportedKitchenDocument,
    outputStream: OutputStream,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  ) = withContext(ioDispatcher) {
    ensureActive()
    val plannedPages = planPdfPages(document, DEFAULT_A4_WIDTH_PT, DEFAULT_A4_HEIGHT_PT)
    val pdfDocument = PdfDocument()
    var activePage: PdfDocument.Page? = null
    try {
      val paints = PdfCulinaryPaints()
      for (plannedPage in plannedPages) {
        ensureActive()
        val info = PdfDocument.PageInfo.Builder(
          DEFAULT_A4_WIDTH_PT, DEFAULT_A4_HEIGHT_PT, plannedPage.pageNumber,
        ).create()
        val page = pdfDocument.startPage(info)
        activePage = page
        drawPlannedPdfPage(page.canvas, document, plannedPage, paints)
        pdfDocument.finishPage(page)
        activePage = null
      }
      // Check coroutine liveness right before flushing encoded PDF bytes to the target stream.
      ensureActive()
      pdfDocument.writeTo(outputStream)
      outputStream.flush()
    } catch (cancel: CancellationException) {
      // Always propagate structured coroutine cancellation without wrapping.
      throw cancel
    } catch (domainError: KitchenBoardException) {
      throw domainError
    } catch (ioError: IOException) {
      val detail = ioError.message ?: "PDF stream write failure"
      throw ValidationException("Unable to save PDF document: $detail", listOf("pdfExport: $detail"), ioError)
    } finally {
      // Finish any in-flight page quietly before close() so Android PdfDocument never throws.
      activePage?.let { unfinished -> runCatching { pdfDocument.finishPage(unfinished) } }
      pdfDocument.close()
    }
  }
}

// Pre-measures a section header row with wrapped title lines and singular/plural item count badge.
private fun buildSectionHeaderRow(section: ExportSection, maxChars: Int): PlannedPdfRow {
  val lines = KitchenPdfExporter.wrapTextToCharBudget(section.sectionTitle, maxChars).ifEmpty { listOf("Section") }
  val countLabel = if (section.itemCount == 1) "1 item" else "${section.itemCount} items"
  return PlannedPdfRow(
    kind = PdfRowKind.SECTION_HEADER,
    primaryLines = lines,
    trailingBadge = countLabel,
    topSpacingPt = SECTION_TOP_SPACING_PT,
    heightPt = 22f + (lines.size - 1) * 13f,
  )
}

// Pre-measures a line item row, reserving horizontal room when a right-aligned badge is present.
private fun buildLineItemRow(
  item: ExportLineItem, badgedChars: Int, wideChars: Int, subtitleChars: Int,
): PlannedPdfRow {
  val badge = item.trailingBadge.trim()
  val primaryBudget = if (badge.isNotEmpty()) badgedChars else wideChars
  val primaryLines = KitchenPdfExporter.wrapTextToCharBudget(item.primaryText, primaryBudget).ifEmpty { listOf("—") }
  val secondaryLines = item.subtitleText?.let { KitchenPdfExporter.wrapTextToCharBudget(it, subtitleChars) }.orEmpty()
  val subtitlePt = if (secondaryLines.isEmpty()) 0f else 2f + secondaryLines.size * 11f
  return PlannedPdfRow(
    PdfRowKind.LINE_ITEM, primaryLines, secondaryLines, badge,
    item.checkboxState, item.isUrgentHighlight, ITEM_TOP_SPACING_PT,
    (10f + primaryLines.size * 13f + subtitlePt).coerceAtLeast(24f),
  )
}

// Allocates anti-aliased Paint brushes once per PDF export run rather than inside per-row loops.
private class PdfCulinaryPaints {
  private val sansRegular: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
  private val sansBold: Typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
  private val serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

  val fill: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  val stroke: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE; strokeWidth = 1f; color = COLOR_DIVIDER_SUBTLE
  }
  val eyebrow: Paint = createInk(8.5f, COLOR_HERBAL_SAGE, sansBold)
  val title: Paint = createInk(18f, COLOR_CHARCOAL_INK, serifBold)
  val subtitle: Paint = createInk(9.5f, COLOR_SLATE_MUTED, sansRegular)
  val metricVal: Paint = createInk(11.5f, COLOR_CHARCOAL_INK, sansBold)
  val metricLbl: Paint = createInk(8f, COLOR_SLATE_MUTED, sansRegular)
  val section: Paint = createInk(11f, COLOR_HERBAL_SAGE, sansBold)
  val itemPrimary: Paint = createInk(10f, COLOR_CHARCOAL_INK, sansBold)
  val itemSecondary: Paint = createInk(8.5f, COLOR_SLATE_MUTED, sansRegular)
  val badge: Paint = createInk(9f, COLOR_SLATE_MUTED, sansBold)
  val footer: Paint = createInk(8f, COLOR_ASH_FOOTER, sansRegular)

  private fun createInk(sizePt: Float, color: Int, tf: Typeface): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = sizePt; this.color = color; typeface = tf }
}

// Draws one complete A4 PDF page: Warm Linen header band, planned body rows, and Page X of Y footer.
private fun drawPlannedPdfPage(
  canvas: Canvas, doc: ExportedKitchenDocument, page: PlannedPdfPage, paints: PdfCulinaryPaints,
) {
  val widthF = DEFAULT_A4_WIDTH_PT.toFloat()
  val heightF = DEFAULT_A4_HEIGHT_PT.toFloat()

  // Paint crisp ceramic white base across the full A4 sheet.
  paints.fill.color = COLOR_PAGE_WHITE
  canvas.drawRect(0f, 0f, widthF, heightF, paints.fill)

  var cursorY = when {
    page.includesDocumentHeader -> drawFirstPageHeaderBand(canvas, doc, widthF, paints)
    else -> drawContinuationHeaderBand(canvas, doc, widthF, paints)
  }

  // Render every pre-measured section header and line item planned onto this page.
  val leftX = PAGE_MARGIN_X_PT
  val rightX = widthF - PAGE_MARGIN_X_PT
  for (row in page.rows) {
    cursorY += row.topSpacingPt
    when (row.kind) {
      PdfRowKind.SECTION_HEADER -> drawSectionHeaderRow(canvas, row, leftX, rightX, cursorY, paints)
      PdfRowKind.LINE_ITEM -> drawLineItemRow(canvas, row, leftX, rightX, cursorY, paints)
    }
    cursorY += row.heightPt
  }

  // Render bottom divider, concise totals summary, and right-aligned "Page X of Y" stamp.
  drawPageFooter(canvas, doc, page, leftX, rightX, heightF, paints)
}

// Draws Page 1's Warm Linen (#FAF7F2) banner, 5pt Herbal Sage (#2E7D5A) stripe, and 4 metric chips.
private fun drawFirstPageHeaderBand(
  canvas: Canvas, doc: ExportedKitchenDocument, widthF: Float, paints: PdfCulinaryPaints,
): Float {
  val hasMetrics = doc.metrics.isNotEmpty()
  val bandBottom = PAGE_TOP_MARGIN_PT +
    if (hasMetrics) FIRST_PAGE_HEADER_WITH_METRICS_PT else FIRST_PAGE_HEADER_COMPACT_PT

  paints.fill.color = COLOR_WARM_LINEN
  canvas.drawRect(0f, 0f, widthF, bandBottom, paints.fill)
  paints.fill.color = COLOR_HERBAL_SAGE
  canvas.drawRect(0f, 0f, widthF, 5f, paints.fill)
  paints.stroke.color = COLOR_DIVIDER_SUBTLE
  paints.stroke.strokeWidth = 1f
  canvas.drawLine(0f, bandBottom, widthF, bandBottom, paints.stroke)

  val leftX = PAGE_MARGIN_X_PT
  canvas.drawText("SAVORAI · OFFLINE FAMILY KITCHEN", leftX, 26f, paints.eyebrow)
  canvas.drawText(ellipsizeChars(doc.title, 54), leftX, 49f, paints.title)
  canvas.drawText(ellipsizeChars(doc.subtitle, 88), leftX, 66f, paints.subtitle)
  if (hasMetrics) drawMetricChipsRow(canvas, doc.metrics.take(4), leftX, widthF - leftX, 80f, paints)
  return bandBottom + 10f
}

// Renders up to 4 equal-width ceramic metric cards inside Page 1's Warm Linen banner.
private fun drawMetricChipsRow(
  canvas: Canvas, chips: List<ExportMetricChip>, leftX: Float, rightX: Float, topY: Float, paints: PdfCulinaryPaints,
) {
  if (chips.isEmpty()) return
  val gapPt = 8f
  val chipWidth = ((rightX - leftX) - gapPt * (chips.size - 1)) / chips.size.toFloat()
  val rect = RectF()
  chips.forEachIndexed { idx, chip ->
    val chipLeft = leftX + idx * (chipWidth + gapPt)
    rect.set(chipLeft, topY, chipLeft + chipWidth, topY + 38f)
    paints.fill.color = COLOR_PAGE_WHITE
    canvas.drawRoundRect(rect, 6f, 6f, paints.fill)
    paints.stroke.color = COLOR_DIVIDER_SUBTLE
    paints.stroke.strokeWidth = 0.9f
    canvas.drawRoundRect(rect, 6f, 6f, paints.stroke)
    canvas.drawText(ellipsizeChars(chip.label, 18), chipLeft + 8f, topY + 14f, paints.metricLbl)
    canvas.drawText(ellipsizeChars(chip.value, 16), chipLeft + 8f, topY + 30f, paints.metricVal)
  }
}

// Draws compact continuation header strip on Pages 2..N to maximize vertical space for items.
private fun drawContinuationHeaderBand(
  canvas: Canvas, doc: ExportedKitchenDocument, widthF: Float, paints: PdfCulinaryPaints,
): Float {
  val bandBottom = PAGE_TOP_MARGIN_PT + CONTINUATION_HEADER_PT
  paints.fill.color = COLOR_WARM_LINEN
  canvas.drawRect(0f, 0f, widthF, bandBottom, paints.fill)
  paints.fill.color = COLOR_HERBAL_SAGE
  canvas.drawRect(0f, 0f, widthF, 3.5f, paints.fill)
  paints.stroke.color = COLOR_DIVIDER_SUBTLE
  paints.stroke.strokeWidth = 0.8f
  canvas.drawLine(0f, bandBottom, widthF, bandBottom, paints.stroke)
  canvas.drawText(ellipsizeChars(doc.title, 64), PAGE_MARGIN_X_PT, 34f, paints.eyebrow)
  drawRightAlignedText(canvas, "Continued", widthF - PAGE_MARGIN_X_PT, 34f, paints.footer)
  return bandBottom + 8f
}

// Draws an aisle or zone section header with Herbal Sage accent bar and right-aligned count pill.
private fun drawSectionHeaderRow(
  canvas: Canvas, row: PlannedPdfRow, leftX: Float, rightX: Float, topY: Float, paints: PdfCulinaryPaints,
) {
  paints.fill.color = COLOR_HERBAL_SAGE
  canvas.drawRoundRect(RectF(leftX, topY + 3f, leftX + 3.5f, topY + row.heightPt - 4f), 2f, 2f, paints.fill)
  row.primaryLines.forEachIndexed { idx, line ->
    canvas.drawText(line, leftX + 10f, topY + 14f + idx * 13f, paints.section)
  }
  if (row.trailingBadge.isNotEmpty()) {
    val pillW = (row.trailingBadge.length * 5.2f + 14f).coerceIn(44f, 96f)
    paints.fill.color = COLOR_SAGE_PILL_BG
    canvas.drawRoundRect(RectF(rightX - pillW, topY + 2f, rightX, topY + 18f), 8f, 8f, paints.fill)
    paints.badge.color = COLOR_HERBAL_SAGE
    drawRightAlignedText(canvas, row.trailingBadge, rightX - 7f, topY + 13f, paints.badge)
  }
  paints.stroke.color = COLOR_DIVIDER_SUBTLE
  paints.stroke.strokeWidth = 0.8f
  canvas.drawLine(leftX, topY + row.heightPt - 1f, rightX, topY + row.heightPt - 1f, paints.stroke)
}

// Draws a checkable grocery item or pantry row with Terracotta highlight for <= 48h urgent stock.
private fun drawLineItemRow(
  canvas: Canvas, row: PlannedPdfRow, leftX: Float, rightX: Float, topY: Float, paints: PdfCulinaryPaints,
) {
  val bottomY = topY + row.heightPt
  if (row.isUrgentHighlight) {
    paints.fill.color = COLOR_URGENT_TINT
    canvas.drawRoundRect(RectF(leftX, topY, rightX, bottomY - 1f), 5f, 5f, paints.fill)
    paints.fill.color = COLOR_TERRACOTTA
    canvas.drawRoundRect(RectF(leftX, topY + 2f, leftX + 3f, bottomY - 3f), 1.5f, 1.5f, paints.fill)
  }
  drawLeadingIndicator(canvas, row.checkboxState, row.isUrgentHighlight, leftX + 8f, topY + 6f, paints)

  val textX = if (row.checkboxState == ExportCheckboxState.NONE) leftX + 20f else leftX + 25f
  paints.itemPrimary.color =
    if (row.checkboxState == ExportCheckboxState.CHECKED) COLOR_SLATE_MUTED else COLOR_CHARCOAL_INK

  var baselineY = topY + 14.5f
  for (line in row.primaryLines) {
    canvas.drawText(line, textX, baselineY, paints.itemPrimary)
    baselineY += 13f
  }
  for (subLine in row.secondaryLines) {
    canvas.drawText(subLine, textX, baselineY - 1f, paints.itemSecondary)
    baselineY += 11f
  }

  if (row.trailingBadge.isNotEmpty()) {
    paints.badge.color = when {
      row.isUrgentHighlight -> COLOR_TERRACOTTA
      row.checkboxState == ExportCheckboxState.UNCHECKED -> COLOR_HERBAL_SAGE
      else -> COLOR_SLATE_MUTED
    }
    drawRightAlignedText(canvas, ellipsizeChars(row.trailingBadge, 34), rightX - 6f, topY + 14.5f, paints.badge)
  }
  if (!row.isUrgentHighlight) {
    paints.stroke.color = COLOR_DIVIDER_SUBTLE
    paints.stroke.strokeWidth = 0.6f
    canvas.drawLine(leftX, bottomY, rightX, bottomY, paints.stroke)
  }
}

// Draws crisp vector UNCHECKED box, CHECKED Herbal Sage checkmark box, or botanical bullet dot.
private fun drawLeadingIndicator(
  canvas: Canvas, state: ExportCheckboxState, isUrgent: Boolean, boxX: Float, boxY: Float, paints: PdfCulinaryPaints,
) {
  val boxRect = RectF(boxX, boxY, boxX + 10.5f, boxY + 10.5f)
  when (state) {
    ExportCheckboxState.UNCHECKED -> {
      paints.fill.color = COLOR_PAGE_WHITE
      canvas.drawRoundRect(boxRect, 2.2f, 2.2f, paints.fill)
      paints.stroke.color = COLOR_SLATE_MUTED
      paints.stroke.strokeWidth = 1.1f
      canvas.drawRoundRect(boxRect, 2.2f, 2.2f, paints.stroke)
    }

    ExportCheckboxState.CHECKED -> {
      paints.fill.color = COLOR_HERBAL_SAGE
      canvas.drawRoundRect(boxRect, 2.2f, 2.2f, paints.fill)
      val checkPath = Path().apply {
        moveTo(boxX + 2.3f, boxY + 5.5f)
        lineTo(boxX + 4.5f, boxY + 7.8f)
        lineTo(boxX + 8.4f, boxY + 3.0f)
      }
      paints.stroke.color = COLOR_PAGE_WHITE
      paints.stroke.strokeWidth = 1.4f
      canvas.drawPath(checkPath, paints.stroke)
    }

    ExportCheckboxState.NONE -> {
      paints.fill.color = if (isUrgent) COLOR_TERRACOTTA else COLOR_HERBAL_SAGE
      canvas.drawCircle(boxX + 3.5f, boxY + 5.2f, 2.4f, paints.fill)
    }
  }
}

// Draws bottom rule, left-aligned footer totals, and "SavorAI Offline Kitchen · Page X of Y".
private fun drawPageFooter(
  canvas: Canvas, doc: ExportedKitchenDocument, page: PlannedPdfPage, leftX: Float, rightX: Float, heightF: Float, paints: PdfCulinaryPaints,
) {
  val dividerY = heightF - 34f
  paints.stroke.color = COLOR_DIVIDER_SUBTLE
  paints.stroke.strokeWidth = 0.8f
  canvas.drawLine(leftX, dividerY, rightX, dividerY, paints.stroke)
  if (doc.footerSummary.isNotBlank()) {
    canvas.drawText(ellipsizeChars(doc.footerSummary, 66), leftX, heightF - 19f, paints.footer)
  }
  val stamp = "SavorAI Offline Kitchen · Page ${page.pageNumber} of ${page.totalPages}"
  drawRightAlignedText(canvas, stamp, rightX, heightF - 19f, paints.footer)
}

private fun drawRightAlignedText(canvas: Canvas, text: String, rightX: Float, y: Float, paint: Paint) {
  val savedAlign = paint.textAlign
  paint.textAlign = Paint.Align.RIGHT
  canvas.drawText(text, rightX, y, paint)
  paint.textAlign = savedAlign
}

private fun ellipsizeChars(text: String, maxChars: Int): String =
  if (text.length <= maxChars) text else "${text.substring(0, (maxChars - 1).coerceAtLeast(1)).trimEnd()}…"
