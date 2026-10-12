import { useEffect, useRef, useState } from "react";
import { X, Send, Sparkles } from "lucide-react";
import buzzAsset from "@/assets/buzz.png";

/**
 * NOTE for whoever wires this up to a real model:
 * This widget ships with a small scripted FAQ brain so it's genuinely
 * useful out of the box with zero backend, but it is NOT calling a live LLM.
 * To make Buzz truly generative, add a Supabase Edge Function (or any small
 * server route) that forwards `messages` to your model of choice and returns
 * `{ reply: string }`, then swap `getBuzzReply()` below for a fetch to it.
 * Never put a real API key in this client-side file.
 */

type Msg = { id: number; from: "buzz" | "user"; text: string };

const QUICK_REPLIES = [
  "How does EzzyHub work?",
  "Is it free?",
  "How do I sign up?",
  "What is Buzz?",
];

function getBuzzReply(input: string): string {
  const q = input.toLowerCase();
  if (
    q.includes("sign up") ||
    q.includes("signup") ||
    q.includes("register") ||
    q.includes("create account")
  ) {
    return "Tap “Get Started” in the top bar, or head to the Sign Up page. It's free — just an email, name, and password, and you can also continue with Google.";
  }
  if (q.includes("sign in") || q.includes("log in") || q.includes("login")) {
    return "You can sign in from the “Sign in” link in the top navigation, or the Sign In page directly. Forgot your password? There's a link for that on the same page.";
  }
  if (q.includes("free") || q.includes("price") || q.includes("cost") || q.includes("pricing")) {
    return "EzzyHub is free to start — private by default, with zero data retention on anything you upload.";
  }
  if (q.includes("how") && (q.includes("work") || q.includes("ezzyhub"))) {
    return "Three steps: drop in your requirements, connect your code (repo, branch, or zip), and I scan for gaps. Anything missing comes back as ready-to-merge AI-written code.";
  }
  if (q.includes("buzz") || q.includes("who are you") || q.includes("what are you")) {
    return "I'm Buzz 🐝 — the hive's guide. I help you find your way around EzzyHub and answer quick questions about how the gap-scan works.";
  }
  if (q.includes("gap") || q.includes("missing") || q.includes("requirement")) {
    return "Every requirement you give me becomes a cell in the hive. Gold cells mean it's implemented — black cells mean it's missing, and I'll generate the code to fill them.";
  }
  if (q.includes("upload") || q.includes("repo") || q.includes("code") || q.includes("connect")) {
    return "You can connect a repo, a branch, or just upload a zip of your project. I scan every file and match it against your requirements list.";
  }
  if (q.includes("thank")) {
    return "Anytime! That's what I'm buzzing around for. 🐝";
  }
  return "I'm still a simple hive guide for now, so I might not have a great answer for that yet — but I can tell you how EzzyHub works, how to sign up, or what Buzz does. What would help most?";
}

export function BuzzChat() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<Msg[]>([
    {
      id: 0,
      from: "buzz",
      text: "Hey, I'm Buzz 🐝 — ask me anything about EzzyHub, or tap a suggestion below.",
    },
  ]);
  const [input, setInput] = useState("");
  const listRef = useRef<HTMLDivElement>(null);
  const idRef = useRef(1);

  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight, behavior: "smooth" });
  }, [messages, open]);

  const send = (text: string) => {
    const trimmed = text.trim();
    if (!trimmed) return;
    const userMsg: Msg = { id: idRef.current++, from: "user", text: trimmed };
    setMessages((m) => [...m, userMsg]);
    setInput("");
    setTimeout(() => {
      const reply: Msg = { id: idRef.current++, from: "buzz", text: getBuzzReply(trimmed) };
      setMessages((m) => [...m, reply]);
    }, 450);
  };

  return (
    <>
      {/* Chat card, anchored just above the mascot button */}
      {open && (
        <div className="fixed z-50 bottom-36 right-4 sm:right-6 w-[calc(100vw-2rem)] sm:w-[380px] max-h-[70vh] flex flex-col animate-panel-in">
          <div className="flex flex-col h-[520px] max-h-[70vh] w-full rounded-3xl bg-card/97 backdrop-blur-2xl border border-[var(--gold)]/25 shadow-[0_30px_90px_rgba(0,0,0,0.65)] overflow-hidden">
            {/* header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-[var(--gold)]/15">
              <div className="flex items-center gap-3">
                <img src={buzzAsset} alt="" className="w-9 h-9 object-contain" />
                <div>
                  <p className="text-sm font-medium text-foreground leading-tight">Buzz</p>
                  <p className="text-[11px] text-muted-foreground flex items-center gap-1">
                    <Sparkles className="w-3 h-3 text-[var(--gold)]" /> EzzyHub hive guide
                  </p>
                </div>
              </div>
              <button
                onClick={() => setOpen(false)}
                className="p-2 rounded-full hover:bg-[var(--gold)]/10 text-muted-foreground hover:text-foreground transition-colors"
                aria-label="Close chat"
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* messages */}
            <div ref={listRef} className="flex-1 overflow-y-auto px-5 py-4 space-y-3">
              {messages.map((m) => (
                <div
                  key={m.id}
                  className={
                    "animate-bubble-in max-w-[85%] text-sm leading-relaxed rounded-2xl px-4 py-2.5 " +
                    (m.from === "buzz"
                      ? "bg-[var(--gold)]/10 border border-[var(--gold)]/20 text-foreground/90"
                      : "bg-[var(--gold)] text-primary-foreground ml-auto")
                  }
                >
                  {m.text}
                </div>
              ))}
            </div>

            {/* quick replies */}
            <div className="px-5 pb-2 flex flex-wrap gap-2">
              {QUICK_REPLIES.map((q) => (
                <button
                  key={q}
                  onClick={() => send(q)}
                  className="text-[11px] px-3 py-1.5 rounded-full border border-[var(--gold)]/25 text-foreground/75 hover:text-[var(--gold)] hover:border-[var(--gold)]/50 transition-colors"
                >
                  {q}
                </button>
              ))}
            </div>

            {/* input */}
            <form
              onSubmit={(e) => {
                e.preventDefault();
                send(input);
              }}
              className="p-4 border-t border-[var(--gold)]/15 flex items-center gap-2"
            >
              <input
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder="Ask Buzz something..."
                className="flex-1 bg-background/60 border border-border rounded-xl px-4 py-2.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:border-[var(--gold)] transition-colors"
              />
              <button
                type="submit"
                className="p-2.5 rounded-xl bg-[var(--gold)] text-primary-foreground hover:shadow-[0_0_20px_rgba(245,191,49,0.5)] transition-shadow"
                aria-label="Send"
              >
                <Send className="w-4 h-4" />
              </button>
            </form>
          </div>
        </div>
      )}

      {/* Big mascot launcher, fixed bottom-right */}
      <button
        onClick={() => setOpen((o) => !o)}
        aria-label={open ? "Close Buzz chat" : "Ask Buzz, the EzzyHub assistant"}
        className="fixed z-50 bottom-4 right-4 sm:bottom-6 sm:right-6 group"
      >
        <span className="relative block">
          <img
            src={buzzAsset}
            alt="Buzz"
            className="w-24 h-24 sm:w-28 sm:h-28 md:w-32 md:h-32 object-contain drop-shadow-[0_20px_35px_rgba(0,0,0,0.6)] animate-float-bee group-hover:scale-105 transition-transform"
          />
          {!open && (
            <span className="absolute top-2 right-2 w-4 h-4 rounded-full bg-[var(--gold)] border-2 border-background animate-ring-pulse" />
          )}
        </span>
      </button>
    </>
  );
}
