# PR Review Research Basis

This reference records the principles used by `flux-pr-review`.

- [GitHub: Reviewing proposed changes in a pull request](https://docs.github.com/en/enterprise-cloud@latest/pull-requests/how-tos/review-pull-requests/reviewing-proposed-changes-in-a-pull-request?tool=webui)
  — understand the PR purpose and linked context, inspect changed files, review
  dependency changes, and distinguish comment, approval, and request-changes
  outcomes.
- [Google Engineering Practices: What to look for in a code review](https://google.github.io/eng-practices/review/reviewer/looking-for.html)
  — review design, functionality, complexity, tests, documentation, context,
  and every changed line rather than relying on a summary.
- [Google Engineering Practices: How to write code review comments](https://google.github.io/eng-practices/review/reviewer/comments.html)
  — explain why, separate required changes from suggestions, and comment on the
  code rather than the author.
- [OWASP Secure Code Review Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Secure_Code_Review_Cheat_Sheet.html)
  — use diff-based, risk-prioritized security review covering trust boundaries,
  input validation, authentication, authorization, business logic, secrets,
  configuration, and dependency changes; manual review complements scanners.

These sources support the review strategy. They do not replace repository-
specific requirements, tests, threat models, or explicit user authority.
