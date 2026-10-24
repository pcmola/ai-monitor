package local.aimonitor;

public enum Provider {
    CLAUDE("Claude Code", "C", 0xffb65e41,
            "https://claude.ai/settings/usage",
            "https://platform.claude.com/cost",
            "https://platform.claude.com/settings/admin-keys",
            "Claude Code 전용 워크스페이스 ID"),
    CODEX("Codex", ">_", 0xff0b826d,
            "https://chatgpt.com/codex/settings/usage",
            "https://platform.openai.com/usage",
            "https://platform.openai.com/settings/organization/admin-keys",
            "Codex 전용 프로젝트 ID");

    public final String title;
    public final String mark;
    public final int color;
    public final String accountUrl;
    public final String apiDashboardUrl;
    public final String keyManagementUrl;
    public final String scopeLabel;

    Provider(String title, String mark, int color, String accountUrl,
             String apiDashboardUrl, String keyManagementUrl, String scopeLabel) {
        this.title = title;
        this.mark = mark;
        this.color = color;
        this.accountUrl = accountUrl;
        this.apiDashboardUrl = apiDashboardUrl;
        this.keyManagementUrl = keyManagementUrl;
        this.scopeLabel = scopeLabel;
    }

    public String billingUrl() {
        return this == CLAUDE ? "https://claude.ai/settings/billing" : "https://chatgpt.com/#settings";
    }
}
