/**
 * 自転車予約管理システム
 * 予約、利用開始、返却、キャンセルの機能を提供
 */

// グローバル変数
let reservationState = 'not_reserved';
let currentReservationId = null;
let startPortId = -1;
let endPortId = -1;
let reserveTimerId = null;
let reserveExpiryAt = null;
let useTimerId = null;
let useStartAt = null;

// API エンドポイント
let API_ENDPOINT = '/bikereservation';

/**
 * 初期化関数（サーバーから設定値を受け取る）
 */
function initBikeReservation(config) {
  startPortId = config.startPortId || -1;
  endPortId = config.endPortId || -1;
  API_ENDPOINT = config.apiEndpoint || '/bikereservation';
}

/**
 * MM:SS形式に変換する
 */
function fmtMMSS(total) {
  const m = Math.floor(total / 60);
  const s = total % 60;
  return (m < 10 ? '0' + m : m) + ':' + (s < 10 ? '0' + s : s);
}

/**
 * 予約カウントダウンを開始（30分）
 */
function startReserveCountdown(seconds) {
  clearReserveCountdown();
  const el = document.getElementById('timerPill');
  el.className = 'timer-pill reserve';
  reserveExpiryAt = Date.now() + seconds * 1000;
  el.style.display = 'inline-flex';
  
  reserveTimerId = setInterval(() => {
    const remain = Math.max(0, Math.floor((reserveExpiryAt - Date.now()) / 1000));
    el.textContent = '予約残り ' + fmtMMSS(remain);
    
    if (remain <= 0) {
      clearReserveCountdown();
      reservationState = 'not_reserved';
      currentReservationId = null;
      updateButtonStates();
      setStatusMessage('予約の有効期限が切れました, 再度予約してください', false);
    }
  }, 1000);
}

/**
 * 予約カウントダウンをクリア
 */
function clearReserveCountdown() {
  if (reserveTimerId) {
    clearInterval(reserveTimerId);
    reserveTimerId = null;
  }
  const el = document.getElementById('timerPill');
  if (el) {
    el.style.display = 'none';
    el.textContent = '';
  }
}

/**
 * 利用時間タイマーを開始
 */
function startUseTimer() {
  clearUseTimer();
  const el = document.getElementById('timerPill');
  el.className = 'timer-pill use';
  useStartAt = Date.now();
  el.style.display = 'inline-flex';
  
  useTimerId = setInterval(() => {
    const sec = Math.floor((Date.now() - useStartAt) / 1000);
    el.textContent = '利用時間 ' + fmtMMSS(sec);
  }, 1000);
}

/**
 * 利用時間タイマーをクリア
 */
function clearUseTimer() {
  if (useTimerId) {
    clearInterval(useTimerId);
    useTimerId = null;
  }
  const el = document.getElementById('timerPill');
  if (el) {
    el.style.display = 'none';
    el.textContent = '';
  }
}

/**
 * ステータスメッセージを表示
 */
function setStatusMessage(text, isSuccess = true) {
  const statusMsg = document.getElementById('statusMsg');
  if (statusMsg) {
    statusMsg.style.display = 'block';
    statusMsg.textContent = (isSuccess ? '✓ ' : '') + text;
  }
}

/**
 * 予約処理
 */
function reserveBike(operatorId) {
  const xhr = new XMLHttpRequest();
  xhr.open('POST', API_ENDPOINT, true);
  xhr.setRequestHeader('Content-Type', 'application/json');
  
  xhr.onreadystatechange = function() {
    if (xhr.readyState === 4) {
      try {
        const response = JSON.parse(xhr.responseText);
        if (response.success) {
          currentReservationId = response.reservation_id;
          reservationState = 'reserved';
          updateButtonStates();
          startReserveCountdown(30 * 60); // 30分
          setStatusMessage('予約しました, 30分以内に利用を開始してください');
        } else {
          alert('予約に失敗しました: ' + response.error);
        }
      } catch (e) {
        alert('エラー: ' + e.message);
      }
    }
  };
  
  const payload = { action: 'reserve', operator_id: operatorId };
  if (startPortId && startPortId > 0) {
    payload.start_port_id = startPortId;
  }
  xhr.send(JSON.stringify(payload));
}

/**
 * 利用開始処理
 */
function startBikeUsage() {
  if (!currentReservationId) return;
  
  const xhr = new XMLHttpRequest();
  xhr.open('POST', API_ENDPOINT, true);
  xhr.setRequestHeader('Content-Type', 'application/json');
  
  xhr.onreadystatechange = function() {
    if (xhr.readyState === 4) {
      try {
        const response = JSON.parse(xhr.responseText);
        if (response.success) {
          reservationState = 'in_use';
          clearReserveCountdown();
          updateButtonStates();
          startUseTimer();
          setStatusMessage('利用中です, 安全運転で!!');
        } else {
          alert('利用開始に失敗しました: ' + response.error);
        }
      } catch (e) {
        alert('エラー: ' + e.message);
      }
    }
  };
  
  xhr.send(JSON.stringify({ action: 'start', reservation_id: currentReservationId }));
}

/**
 * 返却処理
 */
function returnBike() {
  if (!currentReservationId) return;
  
  const xhr = new XMLHttpRequest();
  xhr.open('POST', API_ENDPOINT, true);
  xhr.setRequestHeader('Content-Type', 'application/json');
  
  xhr.onreadystatechange = function() {
    if (xhr.readyState === 4) {
      try {
        const response = JSON.parse(xhr.responseText);
        if (response.success) {
          reservationState = 'returned';
          updateButtonStates();
          clearUseTimer();
          setStatusMessage('自転車を返却しました, ご利用ありがとうございました');
        } else {
          alert('返却に失敗しました: ' + response.error);
        }
      } catch (e) {
        alert('エラー: ' + e.message);
      }
    }
  };
  
  const payload = { action: 'return', reservation_id: currentReservationId };
  if (endPortId && endPortId > 0) {
    payload.return_port_id = endPortId;
  }
  xhr.send(JSON.stringify(payload));
}

/**
 * キャンセル処理
 */
function cancelReservation() {
  if (!currentReservationId) return;
  if (!confirm('予約をキャンセルしますか？')) return;
  
  const xhr = new XMLHttpRequest();
  xhr.open('POST', API_ENDPOINT, true);
  xhr.setRequestHeader('Content-Type', 'application/json');
  
  xhr.onreadystatechange = function() {
    if (xhr.readyState === 4) {
      try {
        const response = JSON.parse(xhr.responseText);
        if (response.success) {
          reservationState = 'not_reserved';
          currentReservationId = null;
          clearReserveCountdown();
          updateButtonStates();
          const statusMsg = document.getElementById('statusMsg');
          if (statusMsg) statusMsg.textContent = '';
        } else {
          alert('キャンセルに失敗しました: ' + response.error);
        }
      } catch (e) {
        alert('エラー: ' + e.message);
      }
    }
  };
  
  xhr.send(JSON.stringify({ action: 'cancel', reservation_id: currentReservationId }));
}

/**
 * ボタン表示状態を更新
 */
function updateButtonStates() {
  const reserveBtn = document.getElementById('reserveBtn');
  const startBtn = document.getElementById('startBtn');
  const returnBtn = document.getElementById('returnBtn');
  const cancelBtn = document.getElementById('cancelBtn');
  
  if (reservationState === 'not_reserved') {
    reserveBtn.style.display = 'inline-block';
    startBtn.style.display = 'none';
    returnBtn.style.display = 'none';
    cancelBtn.style.display = 'none';
  } else if (reservationState === 'reserved') {
    reserveBtn.style.display = 'none';
    startBtn.style.display = 'inline-block';
    returnBtn.style.display = 'none';
    cancelBtn.style.display = 'inline-block';
  } else if (reservationState === 'in_use') {
    reserveBtn.style.display = 'none';
    startBtn.style.display = 'none';
    returnBtn.style.display = 'inline-block';
    cancelBtn.style.display = 'none';
  } else if (reservationState === 'returned') {
    reserveBtn.style.display = 'none';
    startBtn.style.display = 'none';
    returnBtn.style.display = 'none';
    cancelBtn.style.display = 'none';
  }
}

// ラッパー関数（互換性のため）
function reserveBikeUsage() { reserveBike(operatorId); }
function returnBikeUsage() { returnBike(); }
function cancelBikeReservation() { cancelReservation(); }
function startBikeUsageStart() { startBikeUsage(); }
