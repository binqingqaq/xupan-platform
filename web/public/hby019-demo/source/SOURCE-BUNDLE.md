# HBY019 local demo source bundle

Generated from the user-provided local HBY019 rebuild project.
HTML, CSS, JavaScript, fonts, and images are copied from that workspace.
Only static URL prefixes and demo navigation are rewritten for Xupan.
The page HTML, inline styles, source scripts, and local fixtures are served as documents.
Local API bridges provide demo fixtures; they do not call Xupan or HBY019 services.
The player pages are captured client pages plus local runtime/fixture adaptations,
not a complete server source or an unmodified official backend implementation.

The HBY019 source workspace does not contain the chart sprite ball_hk6.png.
No substitute artwork is generated for that unavailable asset.
The login source references /gxagent/fontawesome-webfont.woff; the supplied
FontAwesome file from static/xz/fonts is used for that local reference.
The player trend iframe target is not a captured page. Its local reference server
returns the standard Python 404 document; this bundle preserves that response.
