package app.phueber.senioralert;

import android.content.Context;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * A text that always stays on one line: if it does not fit its width at the
 * requested size, it shrinks just enough to fit. Used for clock times, which
 * must never wrap ("10:3" / "0") however large the phone's font setting is.
 *
 * The platform's autosize needs a fixed height to work reliably; this only
 * needs the width, which the layouts always constrain.
 */
final class OneLineText extends TextView {
    private final float maxPx;

    OneLineText(Context ctx, float sp) {
        super(ctx);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        maxPx = getTextSize(); // the user's font scale is already applied here
        setSingleLine(true);   // even if the shrink fell short, never wrap
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int avail = MeasureSpec.getSize(widthSpec) - getCompoundPaddingLeft() - getCompoundPaddingRight();
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED && avail > 0) {
            float size = maxPx;
            getPaint().setTextSize(size);
            float w = getPaint().measureText(getText().toString());
            if (w > avail) size = (float) Math.floor(size * avail / w);
            if (size != getTextSize()) setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            else getPaint().setTextSize(size);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
