"""Domain layer: business rules, no FastAPI, no request objects.

`identity` owns the account and credential lifecycle — it is the only
module that writes `users`, `api_tokens` and `safety_config`
(architecture doc §1.4, §4).
"""
