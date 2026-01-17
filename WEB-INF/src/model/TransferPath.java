package model;

/**
 * 乗換経路情報
 * @walk0 出発地 -> 1本目乗車停留所 徒歩経路
 * @leg1 乗り物1 乗車経路
 * @walk1 乗換徒歩経路
 * @leg2 乗り物2 乗車経路
 * @walk2 最後の徒歩経路
 * @totalMin 総所要時間(分)
 * @startTime 出発時刻 "HH:mm"
 * @endTime 到着時刻 "HH:mm"
 */
public class TransferPath {
    public final WalkPath walk0;
    public final TransitPath leg1;
    public final WalkPath walk1;
    public final TransitPath leg2;
    public final WalkPath walk2;
    public final int totalMin;
    public final String startTime;
    public final String endTime;

    public TransferPath(WalkPath walk0, TransitPath leg1, WalkPath walk1, TransitPath leg2, WalkPath walk2, int totalMin, String startTime, String endTime) {
        this.walk0 = walk0;
        this.leg1 = leg1;
        this.walk1 = walk1;
        this.leg2 = leg2;
        this.walk2 = walk2;
        this.totalMin = totalMin;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}