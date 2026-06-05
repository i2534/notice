VENV := .venv
PYTHON := $(VENV)/bin/python
PIP := $(VENV)/bin/pip
LOG_FILE := opencode.log
CONFIG := $(if $(wildcard config.yaml.local),config.yaml.local,config.yaml)

.PHONY: prepare start stop logs clean

prepare:
	python3 -m venv $(VENV)
	$(PIP) install -r requirements.txt

start:
	@nohup $(PYTHON) opencode.py --config $(CONFIG) > $(LOG_FILE) 2>&1 & echo $$! > .pid
	@echo "Started (PID: $$(cat .pid), config: $(CONFIG))"

stop:
	@if [ -f .pid ]; then kill $$(cat .pid) 2>/dev/null; rm -f .pid; echo "Stopped"; else echo "Not running"; fi

logs:
	@tail -f $(LOG_FILE)

clean:
	rm -rf $(VENV) __pycache__ *.log .pid
