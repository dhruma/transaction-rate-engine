"use strict";

// Minimal vanilla JS — no build step. Talks to the same REST API documented in Swagger.

const $ = (id) => document.getElementById(id);

function show(el, text, isError) {
  el.textContent = text;
  el.classList.add("show");
  el.classList.toggle("error", !!isError);
}

// Build the table with DOM nodes + textContent (never innerHTML) so user-supplied
// values such as `description` cannot inject HTML/script (stored-XSS safe).
function showTable(el, rows) {
  el.classList.remove("error");
  el.classList.add("show");
  el.replaceChildren();
  const table = document.createElement("table");
  for (const [k, v] of rows) {
    const tr = document.createElement("tr");
    const tdK = document.createElement("td");
    tdK.textContent = k;
    const tdV = document.createElement("td");
    const strong = document.createElement("strong");
    strong.textContent = v == null ? "" : String(v);
    tdV.appendChild(strong);
    tr.append(tdK, tdV);
    table.appendChild(tr);
  }
  el.appendChild(table);
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

  const nav =
    '<div class="btn-row" style="margin-top:12px">' +
    `<button type="button" class="secondary" id="prevPage"` +
    `${txState.page === 0 ? " disabled" : ""}>&#9664; Prev</button>` +
    `<span class="hint">Page ${txState.page + 1} of ${pageCount}</span>` +
    `<button type="button" class="secondary" id="nextPage"` +
    `${txState.page >= pageCount - 1 ? " disabled" : ""}>Next &#9654;</button>` +
    "</div>";

  // Static scaffold only (no user data); rows are built via DOM below.
  wrap.innerHTML =
    countLine +
    '<table class="tx-table"><thead><tr>' +
    "<th>Id</th><th>Description</th><th>Date</th><th>Amount (USD)</th>" +
    '</tr></thead><tbody id="txBody"></tbody></table>' +
    nav;

  const tbody = $("txBody");
  for (const t of pageRows) {
    const tr = document.createElement("tr");
    const cells = [
      [t.id, "tx-id"],
      [t.description, ""],
      [t.transactionDate, ""],
      [t.purchaseAmount, ""],
    ];
    for (const [val, cls] of cells) {
      const td = document.createElement("td");
      if (cls) td.className = cls;
      td.textContent = val == null ? "" : String(val);
      tr.appendChild(td);
    }
    tr.addEventListener("click", () => selectTransaction(t.id, tr));
    tbody.appendChild(tr);
  }
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

function today() {
  return new Date().toISOString().slice(0, 10);
}

$("storeBtn").addEventListener("click", async () => {
  const out = $("storeResult");
  const description = $("description").value.trim();
  const transactionDate = $("date").value;
  const amount = $("amount").value;
  // All fields are required — fail fast in the UI before hitting the API.
  if (!description || !transactionDate || amount === "") {
    show(out, "Description, transaction date, and amount are all required.", true);
    return;
  }
  const payload = {
    description,
    transactionDate,
    purchaseAmount: amount,
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
  $("description").value = "";
  $("amount").value = "";
  $("date").value = today(); // keep the date prefilled to today
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
      ...(c.isoCode ? [["ISO code", c.isoCode]] : []),
      ["Exchange rate", c.exchangeRate],
      ["Rate date", c.exchangeRateDate],
      ["Converted amount", c.convertedAmount],
    ]);
  } catch (e) {
    show(out, "Network error: " + e.message, true);
  }
});

// Populate the currency dropdown: USD first, then Treasury currencies labelled with their
// ISO code when known. Each option's value is what the convert endpoint expects.
(async () => {
  const sel = $("currency");
  try {
    const res = await fetch("/api/currencies");
    const list = res.ok ? await res.json() : [];
    sel.innerHTML = list.length
      ? list
          .map((c) => `<option value="${c.value}">${c.label}</option>`)
          .join("")
      : '<option value="">No currencies available</option>';
  } catch {
    sel.innerHTML = '<option value="">Could not load currencies</option>';
  }
})();

// Prefill the transaction date with today's date.
$("date").value = today();

loadTransactions();
