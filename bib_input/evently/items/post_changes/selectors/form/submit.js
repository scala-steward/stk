function() {
  var form = $(this)[0];
  var bib = form["bib"].value;
  var ts = form["ts"].value;
  if (bib == "" || ts == "") return false;

  var app = $$(this).app;
  $(this).parent().hide('slow');

  call_with_checkpoints(bib, app, function(checkpoints) {
    remove_checkpoint(checkpoints, ts);
    app.db.saveDoc(checkpoints);
  });
  return false;
};