package model;

import java.util.List;

/**
 * 経路検索結果モデル
 * @originStop 出発地停留所
 * @destStop 目的地停留所
 * @results 検索結果一覧
 * @displayedResults 表示対象の検索結果一覧（ページング対応）
 * @stopsNearOrigin 出発地近傍の停留所一覧
 * @stopsNearDest 目的地近傍の停留所一覧
 * @portsNearOrigin 出発地近傍のポート一覧
 * @portsNearDest 目的地近傍のポート一覧
 * @lastOriginStopName 最後に検索した出発地停留所名（候補選択画面での表示用）
 * @lastOriginStopType 最後に検索した出発地停留所タイプ（候補選択画面での表示用）
 * @lastDestStopName 最後に検索した目的地停留所名（候補選択画面での表示用）
 * @lastDestStopType 最後に検索した目的地停留所タイプ（候補選択画面での表示用）
 */
public class RouteResult {
    public Stop originStop;
    public Stop destStop;
    public List<ResultItem> results;
    public List<ResultItem> displayedResults;
    public List<NearByStops> stopsNearOrigin;
    public List<NearByStops> stopsNearDest;
    public List<NearByPorts> portsNearOrigin;
    public List<NearByPorts> portsNearDest;
    public String lastOriginStopName;
    public String lastOriginStopType;
    public String lastDestStopName;
    public String lastDestStopType;
}