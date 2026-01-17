package model;

/**
 * 自転車直行経路プラン
 * @walk0 出発地から自転車乗車地点までの徒歩経路
 * @bike 自転車乗車経路
 * @walk2 自転車降車地点から目的地までの徒歩経路
 * @totalMin 総所要時間(分)
 * @startTime 出発時刻 "HH:mm"
 * @endTime 到着時刻 "HH:mm"
 */
public class BikeDirectPlan {
    public final WalkPath walk0;
    public final BikePath bike;
    public final WalkPath walk2;
    public final int totalMin;
    public final String startTime;
    public final String endTime;

    public BikeDirectPlan(WalkPath walk0, BikePath bike, WalkPath walk2, int totalMin, String startTime, String endTime) {
        this.walk0 = walk0;
        this.bike = bike;
        this.walk2 = walk2;
        this.totalMin = totalMin;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}