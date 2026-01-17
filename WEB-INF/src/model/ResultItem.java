package model;

import java.time.LocalTime;

/**
 * 検索結果の1案分の情報
 * @kind 経路種別 (1: 徒歩のみ, 2: 直通, 3: 乗換)
 * @end 到着時刻 (ソートキー1)
 * @totalMinutes 総所要時間(分) (ソートキー2)
 * @firstRoute 乗換経路の最初の乗り物の路線名 (ソートキー3)
 * @payload 各種経路プランオブジェクト
 */
public class ResultItem {
    public final int kind;
    public final LocalTime end; // ソートキー1
    public final int totalMinutes; // ソートキー2
    public final String firstRoute; // ソートキー3
    public final Object payload; // WalkDirectPlan / BikeDirectPlan / DirectPlan / TransferPath

    public ResultItem(int kind, String endTime, int totalMinutes, String firstRoute, Object payload) {
        this.kind = kind;
        this.end = LocalTime.parse(endTime);
        this.totalMinutes = totalMinutes;
        this.firstRoute = firstRoute;
        this.payload = payload;
    }
}