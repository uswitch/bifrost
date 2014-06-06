# Design

We are currently working on a detailed rationale for the design.

## Interactive Development

The project includes a `user` namespace in `./dev/user.clj`, this is
added as a source path for the development profile in Leiningen. Because
component allows the graceful stopping and starting of components, we
can create a CIDER session in Emacs and use a keybinding (perhaps
`command-r`) to stop and start the whole system.

```elisp
(defun cider-system-reset ()
  (interactive)
  (cider-interactive-eval
   "(user/reset)"))

(define-key clojure-mode-map (kbd "s-r") 'cider-system-reset)
```
