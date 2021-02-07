if (process.env.NODE_ENV === "production") {
  const opt = require("./front-opt.js");
  opt.main();
  module.exports = opt;
} else {
  var exports = window;
  exports.require = require("./front-fastopt-entrypoint.js").require;
  window.global = window;

  const fastOpt = require("./front-fastopt.js");
  fastOpt.main()
  module.exports = fastOpt;

  if (module.hot) {
      module.hot.accept();
  }
}
