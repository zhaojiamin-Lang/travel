#!/usr/bin/env python
"""通过真实导游 API 采集回答，并用 Ragas 评估生成与证据质量。"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
from pathlib import Path
from typing import Any

import requests


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "docs" / "rag-evaluation" / "answer-eval-cases.json"
DEFAULT_OUTPUT = ROOT / "docs" / "rag-evaluation"
ENV_FILE = ROOT / "backend" / ".env.properties"


def read_java_properties(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip()
    return values


def normalize_openai_base_url(value: str) -> str:
    base = value.rstrip("/")
    return base if base.endswith("/v1") else base + "/v1"


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(round((len(ordered) - 1) * ratio))))
    return ordered[index]


def collect_answers(
    api_url: str,
    session_id: str,
    cases: list[dict[str, Any]],
) -> list[dict[str, Any]]:
    client = requests.Session()
    health = client.get(f"{api_url}/api/guide/sessions/{session_id}", timeout=20)
    health.raise_for_status()
    current_poi = health.json().get("currentPoi") or {}
    if not current_poi:
        raise RuntimeError("评测会话还没有选择景点")

    rows: list[dict[str, Any]] = []
    for case in cases:
        response = client.post(
            f"{api_url}/api/guide/sessions/{session_id}/ask",
            json={"question": case["question"]},
            timeout=120,
        )
        response.raise_for_status()
        answer = response.json()
        citations = answer.get("citations") or []
        claims = answer.get("claims") or []
        factual_claims = [
            claim for claim in claims if claim.get("support") in {"CITED", "UNSUPPORTED"}
        ]
        unsupported = [
            claim for claim in factual_claims if claim.get("support") == "UNSUPPORTED"
        ]
        gold_docs = set(case["goldDocIds"])
        cited_docs = [citation.get("docId") for citation in citations]
        citation_matches = sum(1 for doc_id in cited_docs if doc_id in gold_docs)
        retrieval = answer.get("retrieval") or {}
        rows.append(
            {
                "id": case["id"],
                "question": case["question"],
                "answer": answer.get("answer", ""),
                "reference": case["reference"],
                "referenceContexts": case["referenceContexts"],
                "retrievedContexts": [
                    citation.get("quote", "") for citation in citations if citation.get("quote")
                ],
                "citationDocIds": cited_docs,
                "citationCount": len(citations),
                "factualClaimCount": len(factual_claims),
                "unsupportedClaimCount": len(unsupported),
                "citationPrecision": (
                    citation_matches / len(citations) if citations else 0.0
                ),
                "latencyMs": retrieval.get("latencyMs", 0),
                "cacheHit": retrieval.get("cacheHit", False),
                "llmCalls": retrieval.get("llmCalls", 0),
            }
        )
        print(
            f"[collected] {case['id']} citations={len(citations)} "
            f"unsupported={len(unsupported)} latency={retrieval.get('latencyMs', 0)}ms"
        )
    return rows


def evaluate_with_ragas(rows: list[dict[str, Any]], properties: dict[str, str]) -> dict[str, float]:
    from langchain_openai import ChatOpenAI, OpenAIEmbeddings
    from ragas import EvaluationDataset, evaluate
    from ragas.dataset_schema import SingleTurnSample
    from ragas.embeddings import LangchainEmbeddingsWrapper
    from ragas.llms import LangchainLLMWrapper
    from ragas.metrics import AnswerCorrectness, ContextPrecision, ContextRecall, Faithfulness

    api_key = properties.get("travel.ai.api-key", "")
    if not api_key:
        raise RuntimeError("backend/.env.properties 未配置 travel.ai.api-key")
    base_url = normalize_openai_base_url(
        properties.get("travel.ai.base-url", "https://dashscope.aliyuncs.com/compatible-mode")
    )
    model = properties.get("travel.ai.model", "qwen-plus")
    embedding_model = properties.get("travel.ai.embedding-model", "text-embedding-v3")

    judge_model = ChatOpenAI(
        api_key=api_key,
        base_url=base_url,
        model=model,
        temperature=0,
        timeout=120,
        max_retries=3,
    )
    judge = LangchainLLMWrapper(judge_model)
    embeddings = LangchainEmbeddingsWrapper(
        OpenAIEmbeddings(
            api_key=api_key,
            base_url=base_url,
            model=embedding_model,
            check_embedding_ctx_length=False,
            chunk_size=10,
        )
    )
    samples = [
        SingleTurnSample(
            user_input=row["question"],
            response=row["answer"],
            retrieved_contexts=row["retrievedContexts"],
            reference=row["reference"],
            reference_contexts=row["referenceContexts"],
        )
        for row in rows
    ]
    result = evaluate(
        EvaluationDataset(samples=samples),
        metrics=[
            Faithfulness(llm=judge),
            AnswerCorrectness(llm=judge, embeddings=embeddings),
            ContextPrecision(llm=judge),
            ContextRecall(llm=judge),
        ],
        llm=judge,
        embeddings=embeddings,
        raise_exceptions=False,
        show_progress=True,
        batch_size=2,
    )
    frame = result.to_pandas()
    metric_columns = [
        "faithfulness",
        "answer_correctness",
        "context_precision",
        "context_recall",
    ]
    summary: dict[str, float] = {}
    for column in metric_columns:
        if column in frame:
            summary[column] = float(frame[column].dropna().mean())
        else:
            summary[column] = float("nan")
    return summary


def write_outputs(rows: list[dict[str, Any]], ragas_summary: dict[str, float], output: Path) -> None:
    output.mkdir(parents=True, exist_ok=True)
    csv_path = output / "ragas-answer-results.csv"
    with csv_path.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(
            stream,
            fieldnames=[
                "id",
                "question",
                "answer",
                "citationCount",
                "citationDocIds",
                "citationPrecision",
                "factualClaimCount",
                "unsupportedClaimCount",
                "latencyMs",
                "cacheHit",
                "llmCalls",
            ],
        )
        writer.writeheader()
        for row in rows:
            values = {key: row[key] for key in writer.fieldnames}
            values["citationDocIds"] = "|".join(row["citationDocIds"])
            writer.writerow(values)

    factual = sum(row["factualClaimCount"] for row in rows)
    unsupported = sum(row["unsupportedClaimCount"] for row in rows)
    latencies = [float(row["latencyMs"]) for row in rows]
    summary = {
        "cases": len(rows),
        "unsupported_claim_rate": unsupported / factual if factual else 0.0,
        "citation_precision": statistics.fmean(
            [float(row["citationPrecision"]) for row in rows]
        ),
        "latency_p50_ms": statistics.median(latencies),
        "latency_p95_ms": percentile(latencies, 0.95),
        "cache_hits": sum(1 for row in rows if row["cacheHit"]),
        "llm_calls": sum(int(row["llmCalls"]) for row in rows),
        "ragas": ragas_summary,
    }
    (output / "ragas-summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-url", default="http://localhost:18080")
    parser.add_argument("--session-id", required=True)
    parser.add_argument("--cases", type=Path, default=DEFAULT_CASES)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--collect-only", action="store_true")
    parser.add_argument("--limit", type=int, default=0, help="仅运行前 N 条，用于连通性检查")
    args = parser.parse_args()

    cases = json.loads(args.cases.read_text(encoding="utf-8"))
    if args.limit > 0:
        cases = cases[:args.limit]
    rows = collect_answers(args.api_url.rstrip("/"), args.session_id, cases)
    ragas_summary = (
        {}
        if args.collect_only
        else evaluate_with_ragas(rows, read_java_properties(ENV_FILE))
    )
    write_outputs(rows, ragas_summary, args.output)


if __name__ == "__main__":
    main()
