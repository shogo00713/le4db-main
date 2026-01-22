package util;

/**
 * 経路検索で使用する定数を一箇所に集約
 */
public class RouteConstants {

    // 時間関係定数
    public static final int TRANSFER_MIN = 2; // 乗り換えするのに必要な最低時間
    public static final int BIKE_UNLOCK_MIN = 2; // 借りる / 解錠 の最低時間
    public static final int BIKE_LOCK_MIN = 2; // 返す / 施錠 の最低時間

    // 距離関係定数
    public static final int FROM_RADIUS_M = 1000; // 出発地周りの徒歩圏最大
    public static final int TO_RADIUS_M = 1000; // 到着地周りの徒歩圏最大
    public static final int TRANSFER_RADIUS_M = 400; // 乗換の徒歩圏最大
    public static final int BIKE_PORT_RADIUS_M = 200; // 停留所 と ポート間の徒歩圏最大
    public static final int BIKE_MAX_RIDE_M = 6000; // 自転車移動の最大距離（暴走防止）
    // 探索関係定数
    public static final int MID_LIMIT = 10; // 乗り換え地点候補の探索数上限
    public static final int NEAR_LIMIT = 30; // 乗換経路探索数上限
    public static final int PORT_LIMIT = 5; // 近隣ポートの探索数上限
    public static final int RESULT_LIMIT = 5; // 表示する乗換経路の最大
    public static final int BIKE_DIRECT_LIMIT = 2; // 残す自転車直行経路の最大

    // 徒歩/自転車速度関係定数
    public static final double METER_CORRECTION = 1.25; // 徒歩距離補正係数 (直線 -> 道のり)
    public static final double METER_PER_MINUTE = 80.0; // 徒歩の速さは 80m/分
    public static final double BIKE_METER_CORRECTION = 1.5; // 自転車距離補正係数 (直線 -> 自転車通行可能な道のり)
    public static final double BIKE_METER_PER_MINUTE = 250.0; // 自転車の速さは 250m/分

}