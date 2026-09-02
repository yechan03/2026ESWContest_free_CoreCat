package com.example.in_gps.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.widget.TextView;

import com.example.in_gps.R;
import com.github.mikephil.charting.components.MarkerView;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.utils.MPPointF;

/**
 * 차트 터치 스크럽용 말풍선 마커 (벤치마킹 B안: Netatmo/Nest 스타일).
 * 터치·드래그한 지점의 값을 "표면 24.3°C · 14:00" 형태로 표시한다.
 * 라벨 문자열 구성은 프래그먼트가 {@link LabelFormatter}로 주입 —
 * 뷰(1일=시각, 집계=날짜, 실시간=HH:mm:ss)마다 x → 라벨 해석이 다르기 때문.
 */
@SuppressLint("ViewConstructor")
public class TempMarkerView extends MarkerView {

    public interface LabelFormatter {
        String format(Entry e, Highlight highlight);
    }

    private final TextView tvLabel;
    private final LabelFormatter formatter;

    public TempMarkerView(Context context, LabelFormatter formatter) {
        super(context, R.layout.marker_temp);
        this.tvLabel = findViewById(R.id.tv_marker);
        this.formatter = formatter;
    }

    @Override
    public void refreshContent(Entry e, Highlight highlight) {
        tvLabel.setText(formatter.format(e, highlight));
        super.refreshContent(e, highlight);
    }

    /** 터치 지점 위 중앙에 말풍선이 오도록 오프셋. */
    @Override
    public MPPointF getOffset() {
        return new MPPointF(-(getWidth() / 2f), -getHeight() - 12f);
    }

    /** 차트 경계 밖으로 말풍선이 잘려 나가지 않게 클램프.
        (최고점·좌우 끝 지점을 잡았을 때 잘리는 문제 보정) */
    @Override
    public MPPointF getOffsetForDrawingAtPoint(float posX, float posY) {
        MPPointF base = getOffset();
        float ox = base.x, oy = base.y;
        float w = getWidth(), h = getHeight();

        if (posX + ox < 0) {
            ox = -posX;                                   // 왼쪽 잘림 방지
        } else if (getChartView() != null && posX + ox + w > getChartView().getWidth()) {
            ox = getChartView().getWidth() - posX - w;    // 오른쪽 잘림 방지
        }
        if (posY + oy < 0) {
            oy = 8f;                                      // 위 잘림 → 점 아래로 표시
        }
        return new MPPointF(ox, oy);
    }
}
