"""One local fake-driver check: passive queue inspection never publishes or consumes."""
import sys
from types import SimpleNamespace
from unittest.mock import Mock, patch
import collector

connection = Mock()
connection.is_open = True
connection.channel.return_value.queue_declare.return_value.method = SimpleNamespace(message_count=0, consumer_count=1)
pika = SimpleNamespace(ConnectionParameters=Mock(), PlainCredentials=Mock(), BlockingConnection=Mock(return_value=connection))
with patch.dict(sys.modules, {'pika': pika}):
    result = collector.check_rabbitmq({'host':'127.0.0.1','port':5673,'virtual_host':'notifications',
        'username':'test','password':'private-test','queue':'test-existing'}, 2)
assert result == {'rabbitmq_queue_read_success':1,'rabbitmq_queue_messages':0,'rabbitmq_queue_consumers':1}
connection.channel.return_value.queue_declare.assert_called_once_with(queue='test-existing', passive=True)
connection.channel.return_value.basic_publish.assert_not_called()
connection.channel.return_value.basic_consume.assert_not_called()
connection.close.assert_called_once()
print('AMQP passive queue check passed')
