function(cb) {
  var app = $$(this).app;

  function title_cb (result) {
    cb({site_id:result[0][0]["site-id"], infos:result[1][0]});
  }

  fork([
    function(cb) { get_doc(app, cb, "_local/site-info") },
    function(cb) { get_doc(app, cb, "infos") }
  ], title_cb);

}

