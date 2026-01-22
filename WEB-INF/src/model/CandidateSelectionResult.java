package model;

import java.util.ArrayList;
import java.util.List;

/**
 * 停留所候補選択結果
 * @status OK: 正常終了、NOT_FOUND: 停留所候補が見つからなかった、NEED_CHOICE: 停留所候補の選択が必要
 * @message ユーザ向けメッセージ
 * @originStopId 出発停留所ID
 * @destStopId 目的地停留所ID
 * @originCandidates 出発停留所候補リスト
 * @destCandidates 目的地停留所候補リスト
 * @fixedOriginStop 確定した出発停留所（候補選択が不要な場合に設定される）
 * @fixedDestStop 確定した目的地停留所（候補選択が不要な場合に設定される）
 */
public class CandidateSelectionResult {
    public enum Status { OK, NOT_FOUND, NEED_CHOICE }

    public Status status = Status.OK;
    public String message;

    public Integer originStopId;
    public Integer destStopId;

    public List<Stop> originCandidates = new ArrayList<>();
    public List<Stop> destCandidates   = new ArrayList<>();

    public Stop fixedOriginStop;
    public Stop fixedDestStop;
}
