"""Nạp và chạy Isolation Forest đã huấn luyện offline (D40)."""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

import joblib

from app.features import FEATURE_NAMES, to_vector

log = logging.getLogger(__name__)

MODEL_FILE = "isolation_forest.joblib"
META_FILE = "model_meta.json"


@dataclass(frozen=True)
class Score:
    anomaly_score: float
    risk_level: str


class ModelBundle:
    def __init__(self, estimator: Any, meta: Mapping[str, Any]) -> None:
        features = tuple(meta.get("features", ()))
        if features != FEATURE_NAMES:
            # Model cũ với danh sách feature khác: chấm điểm sai còn tệ hơn không chấm
            raise ValueError("model_meta.json không khớp FEATURE_NAMES của service")
        thresholds = meta.get("thresholds", {})
        self.estimator = estimator
        self.meta = dict(meta)
        self.version: str = str(meta.get("version", "unknown"))
        self.medium_threshold = float(thresholds["medium"])
        self.high_threshold = float(thresholds["high"])
        if self.medium_threshold > self.high_threshold:
            raise ValueError("ngưỡng medium phải ≤ high")
        self.imputations: dict[str, float] = {k: float(v) for k, v in meta.get("imputations", {}).items()}

    def score(self, features: Mapping[str, float | None]) -> Score:
        vector = to_vector(features, self.imputations)
        # score_samples: càng thấp càng bất thường → đổi dấu để "điểm cao = rủi ro cao"
        anomaly = float(-self.estimator.score_samples([vector])[0])
        if anomaly >= self.high_threshold:
            level = "HIGH"
        elif anomaly >= self.medium_threshold:
            level = "MEDIUM"
        else:
            level = "LOW"
        return Score(anomaly_score=round(anomaly, 4), risk_level=level)

    def describe(self) -> dict[str, Any]:
        return {
            "version": self.version,
            "features": list(FEATURE_NAMES),
            "thresholds": {"medium": self.medium_threshold, "high": self.high_threshold},
            "imputations": self.imputations,
            "trainedAt": self.meta.get("trained_at"),
            "training": self.meta.get("training", {}),
            "metrics": self.meta.get("metrics", {}),
        }


def load_model(model_dir: Path) -> ModelBundle | None:
    """Không có model → None: service vẫn chạy ở chế độ thu dữ liệu (UNSCORED, D42)."""
    model_path, meta_path = model_dir / MODEL_FILE, model_dir / META_FILE
    if not model_path.is_file() or not meta_path.is_file():
        log.warning("Chưa có model tại %s: chạy chế độ UNSCORED", model_dir)
        return None
    meta = json.loads(meta_path.read_text(encoding="utf-8"))
    bundle = ModelBundle(joblib.load(model_path), meta)
    log.info("Đã nạp model %s", bundle.version)
    return bundle
