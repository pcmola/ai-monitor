package local.aimonitor;

public enum Provider {
  CLAUDE("Claude Code", "C", 0xffb65e41, "https://claude.ai/settings/usage"),
  CODEX("Codex", ">_", 0xff0b826d, "https://chatgpt.com/codex/settings/usage");

  public final String title;
  public final String mark;
  public final int color;
  public final String accountUrl;

  Provider(String title, String mark, int color, String accountUrl) {
    this.title = title;
    this.mark = mark;
    this.color = color;
    this.accountUrl = accountUrl;
  }
}
