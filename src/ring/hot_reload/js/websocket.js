var ws;
var reconnectDelay = 1000;

function connect() {
  var protocol = location.protocol === 'https:' ? 'wss:' : 'ws:';
  ws = new WebSocket(protocol + '//' + location.host + {{uri-prefix}});
  ws.onmessage = function(event) {
    var data = JSON.parse(event.data);
    if (data.type === 'reload') {
      reload();
    }
  };
  ws.onopen = function() {
    console.log('[ring-hot-reload] connected');
    reconnectDelay = 1000;
  };
  ws.onclose = function() {
    console.log('[ring-hot-reload] disconnected, reconnecting in ' + reconnectDelay + 'ms');
    setTimeout(connect, reconnectDelay);
    reconnectDelay = Math.min(reconnectDelay * 1.5, 10000);
  };
}

connect();
