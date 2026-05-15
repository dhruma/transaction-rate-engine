"use strict";

// Minimal vanilla JS — no build step. Talks to the same REST API documented in Swagger.

const $ = (id) => document.getElementById(id);

function show(el, text, isError) {
  el.textContent = text;
  el.classList.add("show");
  el.classList.toggle("error", !!isError);
}

function showTable(el, rows) {
  el.classList.remove("error");
  el.classList.add("show");
  el.innerHTML =
    "<table>" +
    rows.map((r) => `<tr><td>${r[0]}</td><td><strong>${r[1]}</strong></td></tr>`).join("") +
    "</table>";
}

async function parseError(res) {
  try {
    const body = await res.json();
    const details = body.details && body.details.length ? " (" + body.details.join("; ") + ")" : "";
    return (body.message || res.statusText) + details;
  } catch {
    return "HTTP " + res.status;
  }
}

function selectTransaction(id, rowEl) {
  $("txId").value = id;
  document.querySelectorAll(".tx-table tr.selected")
    .forEach((tr) => tr.classList.remove("selected"));
  if (rowEl) rowEl.classList.add("selected");
}

// Client-side pagination over the stored-transactions list (newest first).
const PAGE_SIZE = 5;
const txState = { list: [], total: 0, page: 0 };

function renderTxPage() {
  const wrap = $("txListWrap");
  const { list, total } = txState;
  if (!list.length) {
    wrap.innerHTML = '<div class="empty">No transactions stored yet.</div>';
    return;
  }
  const pageCount = Math.ceil(list.length / PAGE_SIZE);
  txState.page = Math.min(Math.max(txState.page, 0), pageCount - 1);
  const start = txState.page * PAGE_SIZE;
  const pageRows = list.slice(start, start + PAGE_SIZE);

  const countLine =
    `<div class="hint" style="margin-bottom:8px">Showing ${start + 1}–` +
    `${start + pageRows.length} of <strong>${total}</strong> stored, newest first. ` +
    `All transactions are persisted.</div>`;

  const table =
    '<table class="tx-table"><thead><tr>' +
    "<th>Id</th><th>Description</th><th>Date</th><th>Amount (USD)</th>" +
    "</tr></thead><tbody>" +
    pageRows
      .map(
        (t) =>
          `<tr data-id="${t.id}"><td class="tx-id">${t.id}</td>` +
          `<td>${t.description}</td><td>${t.transactionDate}</td>` +
          `<td>${t.purchaseAmount}</td></tr>`
      )
      .join("") +
    "</tbody></table>";

  const nav =
    '<div class="btn-row" style="margin-top:12px">' +
    `<button type="button" class="secondary" id="prevPage"` +
    `${txState.page === 0 ? " disabled" : ""}>&#9664; Prev</button>` +
    `<span class="hint">Page ${txState.page + 1} of ${pageCount}</span>` +
    `<button type="button" class="secondary" id="nextPage"` +
    `${txState.page >= pageCount - 1 ? " disabled" : ""}>Next &#9654;</button>` +
    "</div>";

  wrap.innerHTML = countLine + table + nav;
  wrap.querySelectorAll("tbody tr").forEach((tr) =>
    tr.addEventListener("click", () => selectTransaction(tr.dataset.id, tr))
  );
  const prev = $("prevPage");
  const next = $("nextPage");
  if (prev) prev.addEventListener("click", () => { txState.page--; renderTxPage(); });
  if (next) next.addEventListener("click", () => { txState.page++; renderTxPage(); });
}

// Fetches the list (newest first) and renders the first page.
async function loadTransactions() {
  try {
    // Pull up to 100 so several pages are available without re-fetching per page.
    const res = await fetch("/api/transactions?limit=100");
    txState.list = res.ok ? await res.json() : [];
    txState.total = Number(res.headers.get("X-Total-Count")) || txState.list.length;
    txState.page = 0;
    renderTxPage();
  } catch {
    $("txListWrap").innerHTML =
      '<div class="empty">Could not load transactions.</div>';
  }
}

$("storeBtn").addEventListener("click", async () => {
  const out = $("storeResult");
  const payload = {
    description: $("description").value,
    transactionDate: $("date").value,
    purchaseAmount: $("amount").value,
  };
  try {
    const res = await fetch("/api/transactions", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
    });
    if (!res.ok) {
      show(out, "Could not store: " + (await parseError(res)), true);
      return;
    }
    const t = await res.json();
    selectTransaction(t.id, null);
    showTable(out, [
      ["Stored id", t.id],
      ["Description", t.description],
      ["Transaction date", t.transactionDate],
      ["Amount (USD)", t.purchaseAmount],
    ]);
    await loadTransactions();
  } catch (e) {
    show(out, "Network error: " + e.message, true);
  }
});

$("clearBtn").addEventListener("click", () => {
  ["description", "date", "amount"].forEach((id) => ($(id).value = ""));
  const out = $("storeResult");
  out.classList.remove("show", "error");
  out.textContent = "";
  $("description").focus();
});

$("refreshBtn").addEventListener("click", loadTransactions);

$("retrieveBtn").addEventListener("click", async () => {
  const out = $("retrieveResult");
  const id = $("txId").value.trim();
  const currency = $("currency").value;
  if (!id || !currency) {
    show(out, "Pick a transaction (or enter an id) and choose a currency.", true);
    return;
  }
  try {
    const res = await fetch(
      "/api/transactions/" + encodeURIComponent(id) + "?currency=" + encodeURIComponent(currency)
    );
    if (!res.ok) {
      show(out, await parseError(res), true);
      return;
    }
    const c = await res.json();
    showTable(out, [
      ["Id", c.id],
      ["Description", c.description],
      ["Transaction date", c.transactionDate],
      ["Original (USD)", c.originalAmountUsd],
      ["Target currency", c.targetCurrency],
      ["Exchange rate", c.exchangeRate],
      ["Rate date", c.exchangeRateDate],
      ["Converted amount", c.convertedAmount],
    ]);
  } catch (e) {
    show(out, "Network error: " + e.message, true);
  }
});

// Populate the currency dropdown from the live Treasury currency list.
(async () => {
  const sel = $("currency");
  try {
    const res = await fetch("/api/currencies");
    const list = res.ok ? await res.json() : [];
    sel.innerHTML = list.length
      ? list.map((c) => `<option value="${c}">${c}</option>`).join("")
      : '<option value="">No currencies available</option>';
  } catch {
    sel.innerHTML = '<option value="">Could not load currencies</option>';
  }
})();

loadTransactions();
