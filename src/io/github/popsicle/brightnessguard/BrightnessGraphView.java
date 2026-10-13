package io.github.popsicle.brightnessguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

/** A bounded 90-sample graph; lines show logical values, never a photometer measurement. */
public final class BrightnessGraphView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private JSONArray samples=new JSONArray();
    public BrightnessGraphView(Context context) { super(context); setMinimumHeight(dp(150)); }
    public void setSamples(JSONArray values) { samples=values==null?new JSONArray():values; invalidate(); }
    private int dp(int n) { return Math.round(n*getResources().getDisplayMetrics().density); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left=dp(28), top=dp(10), width=getWidth()-left-dp(6), height=getHeight()-top-dp(22);
        if(width<=0 || height<=0) return;
        paint.setStrokeWidth(dp(1)); paint.setStyle(Paint.Style.STROKE); paint.setColor(0xffe2e8f1);
        for(int i=0;i<=4;i++) { float y=top+height*i/4; canvas.drawLine(left,y,left+width,y,paint); }
        paint.setTextSize(dp(10)); paint.setStyle(Paint.Style.FILL); paint.setColor(0xff76869b);
        canvas.drawText("1.0",0,top+dp(4),paint); canvas.drawText("0",0,top+height,paint);
        canvas.drawText("最近 90 秒 · 逻辑亮度",left,top+height+dp(17),paint);
        drawLine(canvas,"output",0xff315fc6,left,top,width,height);
        drawLine(canvas,"request",0xff168977,left,top,width,height);
        drawLine(canvas,"maximum",0xffd18a18,left,top,width,height);
    }
    private void drawLine(Canvas canvas,String key,int color,float left,float top,float width,float height) {
        Path path=new Path(); boolean begun=false; int n=samples.length();
        if(n==0) return;
        JSONObject last=samples.optJSONObject(n-1); long end=last==null?0:last.optLong("uptime_ms");
        for(int i=0;i<n;i++) {
            JSONObject sample=samples.optJSONObject(i); if(sample==null) { begun=false; continue; }
            double value=sample.optDouble(key,Double.NaN);
            if(!BrightnessAssessment.valid(value)) { begun=false; continue; }
            long age=end-sample.optLong("uptime_ms"); if(age<0 || age>90000) { begun=false; continue; }
            float x=left+width*(1-age/90000f), y=top+height*(1-(float)value);
            if(!begun) { path.moveTo(x,y); begun=true; } else path.lineTo(x,y);
        }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(color); canvas.drawPath(path,paint);
    }
}
