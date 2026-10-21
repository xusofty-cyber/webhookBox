import crypto from "node:crypto";

const body = JSON.stringify({
  action: "completed",
  workflow_run: { name: "build", conclusion: "failure" },
  repository: { full_name: "acme/api" },
});

const sig = "sha256=" + crypto.createHmac("sha256", "ghsecret").update(body).digest("hex");

async function send(label, headers) {
  const res = await fetch("http://127.0.0.1:8788/hook/github", {
    method: "POST",
    headers: { "content-type": "application/json", ...headers },
    body,
  });
  console.log(label, res.status, await res.text());
}

await send("合法签名  ", {
  "X-Hub-Signature-256": sig,
  "X-GitHub-Event": "workflow_run",
  "X-GitHub-Delivery": "delivery-abc-123",
});

await send("错误签名  ", {
  "X-Hub-Signature-256": "sha256=" + "0".repeat(64),
  "X-GitHub-Event": "workflow_run",
});

await send("没有签名头", { "X-GitHub-Event": "workflow_run" });

const list = await fetch("http://127.0.0.1:8788/api/messages?token=testtoken&limit=3");
console.log("落库结果 ", await list.text());
