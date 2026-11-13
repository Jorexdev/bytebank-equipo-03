# Fix a corrección en la API de ms-notifications

ms-accounts publica en Kafka cada movimiento y ms-notifications guarda un aviso para el cliente, pero la API para consultarlos responde 501. El contrato está en `docs/contratos/ms-notifications.md`.
