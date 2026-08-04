--[[
  Give each document a title page of its own, and start every major section on
  a fresh page.

  The major-section level is computed per document rather than hard-coded,
  because the four documents do not agree on one: documentation, blog-post and
  feature-summary use `##` as their top level, while release-notes uses `#` for
  each version and `##` for subsections inside it. Hard-coding either would put
  a page break in the middle of one document's sections.

  Two breaks are inserted:

    1. After the front matter — the title block, logo, repository lines and the
       non-production disclaimer, which every document opens with. The
       disclaimer blockquote is the boundary: "before the first heading" is not,
       because the blog post carries several paragraphs and a figure of running
       prose before its first heading, and those belong in the body rather than
       on the title page.
    2. Before every heading at the document's own top level.

  A horizontal rule immediately preceding a break is dropped: the rule was
  there to separate sections on a continuous page, and a page break already
  does that. Left in, it prints as a stray line at the foot of the page.
]]

local function pagebreak()
  if FORMAT:match 'docx' then
    return pandoc.RawBlock('openxml', '<w:p><w:r><w:br w:type="page"/></w:r></w:p>')
  elseif FORMAT:match 'html' then
    return pandoc.RawBlock('html', '<div class="pagebreak"></div>')
  end
  return nil
end

function Pandoc(doc)
  -- A document opts out with `paginate: false` in its own front matter. The
  -- blog post does: it reads as one continuous piece, where a title page and a
  -- break at every heading would work against it.
  if doc.meta.paginate == false then return doc end

  local brk = pagebreak()
  if not brk then return doc end

  local top, first_header = 6, nil
  for i, block in ipairs(doc.blocks) do
    if block.t == 'Header' then
      if not first_header then first_header = i end
      if block.level < top then top = block.level end
    end
  end

  -- The last blockquote ahead of the first heading is the disclaimer, and the
  -- end of the front matter.
  local front_matter_end
  for i, block in ipairs(doc.blocks) do
    if first_header and i >= first_header then break end
    if block.t == 'BlockQuote' then front_matter_end = i end
  end

  local out = {}
  for i, block in ipairs(doc.blocks) do
    if block.t == 'Header' and block.level == top then
      if #out > 0 and out[#out].t == 'HorizontalRule' then
        table.remove(out)
      end
      if out[#out] ~= brk then
        table.insert(out, brk)
      end
    end
    table.insert(out, block)
    if i == front_matter_end then
      table.insert(out, brk)
    end
  end

  doc.blocks = out
  return doc
end
