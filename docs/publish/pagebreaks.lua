--[[
  Paginate a document: give it a title page of its own, and start every major
  section on a fresh page.

  Both are on by default and each can be switched off independently in a
  document's own front matter, because the four documents want three different
  answers:

    title-page: false      no break after the front matter
    section-breaks: false  no break before major sections

  Documentation and release notes take both — they are references, read by
  jumping to a section. The feature summary takes the title page only: it is a
  single graded list, and a page break between its groups separates items meant
  to be read against each other. The blog post takes neither; it reads as one
  continuous piece.

  Keeping the flags in each document rather than in build.sh puts the decision
  where somebody opening the markdown will find it.

  The major-section level is computed per document rather than hard-coded,
  because the four do not agree on one: documentation, blog post and feature
  summary use `##` as their top level, while release notes uses `#` for each
  version and `##` for subsections inside it. Hard-coding either would put a
  page break in the middle of one document's sections.

  The title page ends at the disclaimer blockquote, not at the first heading:
  the blog post carries several paragraphs and a figure of running prose before
  its first heading, and those belong in the body rather than on a title page.

  A horizontal rule immediately preceding a break is dropped. The rule was
  there to separate sections on a continuous page, and a page break already
  does that; left in, it prints as a stray line at the foot of the page.
]]

local function pagebreak()
  if FORMAT:match 'docx' then
    return pandoc.RawBlock('openxml', '<w:p><w:r><w:br w:type="page"/></w:r></w:p>')
  elseif FORMAT:match 'html' then
    return pandoc.RawBlock('html', '<div class="pagebreak"></div>')
  end
  return nil
end

--- A flag is on unless the front matter explicitly says false.
local function enabled(meta, key)
  return meta[key] ~= false
end

function Pandoc(doc)
  local want_title = enabled(doc.meta, 'title-page')
  local want_sections = enabled(doc.meta, 'section-breaks')
  if not want_title and not want_sections then return doc end

  local brk = pagebreak()
  if not brk then return doc end

  local top, first_header = 6, nil
  for i, block in ipairs(doc.blocks) do
    if block.t == 'Header' then
      if not first_header then first_header = i end
      if block.level < top then top = block.level end
    end
  end

  -- The last blockquote ahead of the first heading is the disclaimer, and so
  -- the end of the front matter.
  local front_matter_end
  for i, block in ipairs(doc.blocks) do
    if first_header and i >= first_header then break end
    if block.t == 'BlockQuote' then front_matter_end = i end
  end

  local out = {}
  for i, block in ipairs(doc.blocks) do
    if want_sections and block.t == 'Header' and block.level == top then
      if #out > 0 and out[#out].t == 'HorizontalRule' then
        table.remove(out)
      end
      if out[#out] ~= brk then
        table.insert(out, brk)
      end
    end
    table.insert(out, block)
    if want_title and i == front_matter_end then
      table.insert(out, brk)
    end
  end

  doc.blocks = out
  return doc
end
