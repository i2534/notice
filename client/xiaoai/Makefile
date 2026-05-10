.PHONY: prepare start stop logs test

CONFIG ?= config.yaml

prepare:
	python3 -m venv .venv
	.venv/bin/pip install -r requirements.txt

start:
	@nohup .venv/bin/python3 xiaoai.py --config $(CONFIG) > xiaoai.log 2>&1 & echo $$! > .pid
	@echo "started (pid $$(cat .pid))"

stop:
	@kill $$(cat .pid) 2>/dev/null; rm -f .pid

logs:
	@tail -f xiaoai.log

test:
	.venv/bin/python3 test_xiaoai.py
