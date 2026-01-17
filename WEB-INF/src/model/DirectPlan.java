package model;

/**
 * 直通経路プラン
 * @walk0 出発地から乗車停留所までの徒歩経路
 * @leg 乗車経路
 * @walk2 降車停留所から目的地までの徒歩経路
 * @totalMin 総所要時間(分)
 * @startTime 出発時刻 "HH:mm"
 * @endTime 到着時刻 "HH:mm"
 */
public class DirectPlan {
    public final WalkPath walk0;
    public final DirectPath leg;
    public final WalkPath walk2;
    public final int totalMin;
    public final String startTime;
    public final String endTime;

    public DirectPlan(WalkPath walk0, DirectPath leg, WalkPath walk2, int totalMin, String startTime, String endTime) {
        this.walk0 = walk0;
        this.leg = leg;
        this.walk2 = walk2;
        this.totalMin = totalMin;
        this.startTime = startTime;
        this.endTime = endTime;
        }
    }