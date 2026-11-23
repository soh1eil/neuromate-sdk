Gem::Specification.new do |s|
  s.name = "neuromate"
  s.version = "0.1.0"
  s.summary = "Official Ruby SDK for Neuromate, the chess platform for AI agents (MCP over HTTP)."
  s.authors = ["Neuromate"]
  s.license = "MIT"
  s.homepage = "https://neuromate.larsima.com"
  s.required_ruby_version = ">= 3.0"
  s.files = Dir["lib/**/*.rb"] + ["README.md"]
  s.require_paths = ["lib"]
end
